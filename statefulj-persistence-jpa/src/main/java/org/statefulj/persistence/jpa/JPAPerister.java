
package org.statefulj.persistence.jpa;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;

import javax.persistence.EntityManager;
import javax.persistence.Id;
import javax.persistence.NoResultException;
import javax.persistence.PersistenceContext;

import org.springframework.transaction.annotation.Transactional;
import org.statefulj.fsm.Persister;
import org.statefulj.fsm.StaleStateException;
import org.statefulj.fsm.model.State;

// TODO : Rewrite this to use "safe" query building instead of string construction
//
@Transactional
public class JPAPerister<T> implements Persister<T> {

	@PersistenceContext
	private EntityManager entityManager;
	
	private String clazz;
	private Field idField;
	private Field stateField;
	private State<T> start;
	private HashMap<String, State<T>> states = new HashMap<String, State<T>>();
	
	public JPAPerister(List<State<T>> states, State<T> start, Class<T> clazz) {
		
		// Find the Id and State<T> field of the Entity
		//
		this.clazz = clazz.getSimpleName();
		this.idField = getAnnotatedField(clazz, Id.class);
		
		if (this.idField == null) {
			throw new RuntimeException("No Id field defined");
		}
		this.idField.setAccessible(true);
		
		this.stateField = getAnnotatedField(clazz, org.statefulj.persistence.jpa.annotations.State.class);
		if (this.stateField == null) {
			throw new RuntimeException("No State field defined");
		}
		if (!this.stateField.getType().equals(String.class)) {
			throw new RuntimeException(
					String.format(
							"State field, %s, of class %s, is not of type String",
							this.stateField.getName(),
							clazz));
		}
		this.stateField.setAccessible(true);

		// Start state - returned when no state is set
		//
		this.start = start;
		
		// Index States into a HashMap
		//
		for(State<T> state : states) {
			this.states.put(state.getName(), state);
		}
		
	}

	/**
	 * Return the current State 
	 */
	public State<T> getCurrent(T stateful) {
		State<T> state = null;
		try {
			String stateKey = this.getState(stateful);
			state = (stateKey == null ) ? this.start : this.states.get(stateKey);
		} catch (NoSuchFieldException e) {
			throw new RuntimeException(e);
		} catch (SecurityException e) {
			throw new RuntimeException(e);
		} catch (IllegalArgumentException e) {
			throw new RuntimeException(e);
		} catch (IllegalAccessException e) {
			throw new RuntimeException(e);
		}
		state = (state == null) ? this.start : state;
		return state;
	}

	/**
	 * Set the current State.  This method will ensure that the state in the db matches the expected current state.  
	 * If not, it will throw a StateStateException
	 * 
	 * @param stateful
	 * @param current
	 * @param next
	 * @throws StaleStateException 
	 */
	public void setCurrent(T stateful, State<T> current, State<T> next) throws StaleStateException {
		try {
			
			// Has this Entity been persisted to the database? 
			//
			Object id = getId(stateful);
			if (id != null && entityManager.contains(stateful)) {
				
				// Entity is in the database - perform qualified update based off 
				// the current State value
				//
				String update = buildUpdateStatement(id, stateful, current, next, idField, stateField);
				
				// Successful update?
				//
				if (entityManager.createQuery(update).executeUpdate() == 0) {
					
					// If we aren't able to update - it's most likely that we are out of sync.
					// So, fetch the latest value and update the Stateful object.  Then throw a RetryException
					// This will cause the event to be reprocessed by the FSM
					//
					String query = String.format(
							"select %s from %s where %s=%s", 
							this.stateField.getName(), 
							this.clazz,
							this.idField.getName(),
							id);
					String state = this.start.getName();
					try {
						state = (String)entityManager.createQuery(query).getSingleResult();
					} catch(NoResultException nre) {
						// This is the first time setting the state, ignore
						//
					}
					setState(stateful, state);
					throwStaleState(current, next);
				}
				setState(stateful, next.getName());
			} else {
				
				// The Entity hasn't been persisted to the database - so it exists only
				// this Application memory.  So, serialize the qualified update to prevent
				// concurrency conflicts
				//
				synchronized(stateful) {
					String state = this.getState(stateful);
					state = (state == null) ? this.start.getName() : state;
					if (state.equals(current.getName())) {
						setState(stateful, next.getName());
					} else {
						throwStaleState(current, next);
					}
				}
			}
		} catch (NoSuchFieldException e) {
			throw new RuntimeException(e);
		} catch (SecurityException e) {
			throw new RuntimeException(e);
		} catch (IllegalArgumentException e) {
			throw new RuntimeException(e);
		} catch (IllegalAccessException e) {
			throw new RuntimeException(e);
		}
	}
	
	protected String buildUpdateStatement(
			Object id, 
			T stateful, 
			State<T> current, 
			State<T> next, 
			Field idField, 
			Field stateField) {
		
		String where = (current.equals(this.start)) 
				?
				String.format(
						"%s=%s and (%s='%s' or %s is null)",
						idField.getName(),
						id,
						stateField.getName(), 
						current.getName(),
						stateField.getName()) 
				:
				String.format(
						"%s=%s and %s='%s'",
						idField.getName(),
						id,
						stateField.getName(), 
						current.getName());
		
		String update = String.format(
				"update %s set %s='%s' where %s", 
				clazz, 
				stateField.getName(), 
				next.getName(),
				where);
		
		return update;
	}
	
	/**
	 * Return a field by an Annotation
	 * 
	 * @param clazz
	 * @param annotationClass
	 * @return
	 */
	private Field getAnnotatedField(
			Class<?> clazz,
			Class<? extends Annotation> annotationClass) {
		Field match = null;
		if (clazz != null) {
			match = getAnnotatedField(clazz.getSuperclass(), annotationClass);
			if (match == null) {
				for(Field field : clazz.getDeclaredFields()) {
					if (field.isAnnotationPresent(annotationClass)) {
						match = field;
						break;
					}
				}
				
			}
		}
		
		return match;
	}
	
	private Object getId(T obj) throws NoSuchFieldException, SecurityException, IllegalArgumentException, IllegalAccessException {
		return this.idField.get(obj);
	}
	
	private String getState(T obj) throws NoSuchFieldException, SecurityException, IllegalArgumentException, IllegalAccessException {
		return (String)this.stateField.get(obj);
	}
	
	private void setState(T obj, String state) throws IllegalArgumentException, IllegalAccessException, NoSuchFieldException, SecurityException {
		state = (state == null) ? this.start.getName() : state;
		this.stateField.set(obj, state);
	}

	private void throwStaleState(State<T> current, State<T> next) throws StaleStateException {
		String err = String.format(
				"Unable to update state, entity.state=%s, db.state=%s",
				current.getName(),
				next.getName());
		throw new StaleStateException(err);
	}
}
