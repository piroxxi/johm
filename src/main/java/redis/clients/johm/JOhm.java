package redis.clients.johm;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import redis.clients.jedis.JedisException;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.TransactionBlock;

/**
 * JOhm serves as the delegate responsible for heavy-lifting all mapping
 * operations between the Object Models at one end and Redis Persistence Models
 * on the other.
 * 
 * TODO: make a decision on what API to keep public.
 */
public class JOhm {
    protected Nest nest = null;

    @SuppressWarnings("unchecked")
    public static <T extends Model> T get(Class<? extends Model> clazz, int id) {
        Model newInstance;
        try {
            newInstance = clazz.newInstance();

            if (newInstance.nest.cat(id).exists().intValue() == 0) {
                return null;
            }
            Map<String, String> hashedObject = newInstance.nest.cat(id)
                    .hgetAll();

            for (Field field : clazz.getDeclaredFields()) {
                fillField(hashedObject, newInstance, field);
            }
            for (Field field : clazz.getSuperclass().getDeclaredFields()) {
                fillField(hashedObject, newInstance, field);
            }

            return (T) newInstance;
        } catch (InstantiationException e) {
            throw new JOhmException(e);
        } catch (IllegalAccessException e) {
            throw new JOhmException(e);
        }
    }

    /**
     * Search a Model in redis index using its attribute's given name/value
     * pair. This can potentially return more than 1 matches if some indexed
     * Model's have identical attributeValue for given attributeName.
     * 
     * @param clazz
     *            Class of Model sub-type to search
     * @param attributeName
     *            Name of Model's attribute to search
     * @param attributeValue
     *            Attribute's value to search in index
     * @return
     */
    @SuppressWarnings("unchecked")
    public static <T> List<T> find(Class<? extends Model> clazz,
            String attributeName, Object attributeValue) {
        List<Model> results = null;
        if (!JOhmUtils.isIndexable(attributeName)) {
            throw new InvalidFieldException();
        }

        try {
            Field field = clazz.getDeclaredField(attributeName);
            field.setAccessible(true);
            if (!field.isAnnotationPresent(Indexed.class)) {
                throw new InvalidFieldException();
            }
        } catch (SecurityException e) {
            throw new InvalidFieldException();
        } catch (NoSuchFieldException e) {
            throw new InvalidFieldException();
        }
        if (JOhmUtils.isNullOrEmpty(attributeValue)) {
            throw new InvalidFieldException();
        }
        Nest nest = new Nest(clazz);
        Set<String> modelIdStrings = nest.cat(attributeName)
                .cat(attributeValue).smembers();
        if (modelIdStrings != null) {
            // TODO: Do this lazy
            results = new ArrayList<Model>();
            Model indexed = null;
            for (String modelIdString : modelIdStrings) {
                indexed = get(clazz, Integer.parseInt(modelIdString));
                if (indexed != null) {
                    results.add(indexed);
                }
            }
        }
        return (List<T>) results;
    }

    @SuppressWarnings("unchecked")
    public static <T extends Model> T save(final Model model) {
        try {
            final Map<String, String> hashedObject = new HashMap<String, String>();

            if (model.getId() == null) {
                model.initializeId();
            }
            List<Field> fields = new ArrayList<Field>();
            fields.addAll(Arrays.asList(model.getClass().getDeclaredFields()));
            fields.addAll(Arrays.asList(model.getClass().getSuperclass()
                    .getDeclaredFields()));

            String fieldName = null, fieldValue = null;
            boolean isAttribute = false, isReference = false;
            for (Field field : fields) {
                if (field.isAnnotationPresent(Attribute.class)) {
                    isAttribute = true;
                    field.setAccessible(true);
                    fieldName = field.getName();
                    Object fieldValueObject = field.get(model);
                    if (fieldValueObject != null) {
                        fieldValue = fieldValueObject.toString();
                        hashedObject.put(fieldName, fieldValue);
                    }
                }
                if (field.isAnnotationPresent(Reference.class)) {
                    isReference = true;
                    field.setAccessible(true);
                    fieldName = JOhmUtils.getReferenceKeyName(field);
                    JOhmUtils.checkValidReference(field);
                    Model reference = Model.class.cast(field.get(model));
                    if (reference != null) {
                        if (reference.getId() == null) {
                            throw new MissingIdException();
                        }
                        fieldValue = String.valueOf(reference.getId());
                        hashedObject.put(fieldName, fieldValue);
                    }
                }
                if (field.isAnnotationPresent(Indexed.class)) {
                    if (isAttribute) {
                        if (!JOhmUtils.isIndexable(fieldName)) {
                            throw new InvalidFieldException();
                        }
                        if (!JOhmUtils.isNullOrEmpty(fieldValue)) {
                            model.nest.cat(fieldName).cat(fieldValue).sadd(
                                    String.valueOf(model.getId()));
                        }
                        continue;
                    }
                    if (isReference) {
                        // Revisit this in the future
                        throw new UnsupportedOperationException(
                                "Reference indexing is not yet supported");
                    }
                    throw new UnsupportedOperationException(
                            "Indexing is not supported for unpersisted fields");
                }
            }

            model.nest.multi(new TransactionBlock() {
                public void execute() throws JedisException {
                    del(model.nest.cat(model.getId()).key());
                    hmset(model.nest.cat(model.getId()).key(), hashedObject);
                }
            });
            return (T) model;
        } catch (IllegalArgumentException e) {
            throw new JOhmException(e);
        } catch (IllegalAccessException e) {
            throw new JOhmException(e);
        }
    }

    /**
     * 
     * @param clazz
     * @param id
     * @param deleteChildren
     * @return
     */
    public static boolean delete(Class<? extends Model> clazz, int id,
            boolean deleteChildren) {
        boolean deleted = false;
        Model persistedModel = get(clazz, id);
        if (persistedModel != null) {
            if (deleteChildren) {
                List<Field> fields = new ArrayList<Field>();
                fields.addAll(Arrays.asList(clazz.getDeclaredFields()));
                fields.addAll(Arrays.asList(clazz.getSuperclass()
                        .getDeclaredFields()));
                for (Field field : fields) {
                    if (field.isAnnotationPresent(Reference.class)) {
                        field.setAccessible(true);
                        try {
                            Model child = Model.class.cast(field
                                    .get(persistedModel));
                            if (child != null) {
                                delete(child.getClass(), child.getId(),
                                        deleteChildren); // children
                            }
                        } catch (IllegalArgumentException e) {
                            throw new JOhmException(e);
                        } catch (IllegalAccessException e) {
                            throw new JOhmException(e);
                        }
                    }
                }
            }

            // now delete parent
            deleted = persistedModel.nest.cat(id).del() == 1;
        }
        return deleted;
    }

    /**
     * 
     * @param jedisPool
     */
    public static void setPool(JedisPool jedisPool) {
        Nest.setJedisPool(jedisPool);
    }

    protected JOhm() {
        nest = new Nest(this);
    }

    @SuppressWarnings("unchecked")
    private static void fillField(final Map<String, String> hashedObject,
            final Model newInstance, final Field field)
            throws IllegalAccessException {
        if (field.isAnnotationPresent(Attribute.class)) {
            field.setAccessible(true);
            field.set(newInstance, JOhmUtils.convert(field, hashedObject
                    .get(field.getName())));
        }
        if (field.isAnnotationPresent(Reference.class)) {
            JOhmUtils.checkValidReference(field);
            field.setAccessible(true);
            String serializedReferenceId = hashedObject.get(JOhmUtils
                    .getReferenceKeyName(field));
            if (serializedReferenceId != null) {
                Integer referenceId = Integer.valueOf(serializedReferenceId);
                field.set(newInstance, get((Class<? extends Model>) field
                        .getType(), referenceId));
            }
        }
    }

}