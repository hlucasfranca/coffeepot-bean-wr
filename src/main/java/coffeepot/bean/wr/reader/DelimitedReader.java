/*
 * Copyright 2015 Jeandeson O. Merelis.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package coffeepot.bean.wr.reader;

/*
 * #%L
 * coffeepot-bean-wr
 * %%
 * Copyright (C) 2013 - 2015 Jeandeson O. Merelis
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
import coffeepot.bean.wr.parser.FieldImpl;
import coffeepot.bean.wr.parser.ObjectMapper;
import coffeepot.bean.wr.parser.ObjectMapperFactory;
import coffeepot.bean.wr.types.FormatType;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author Jeandeson O. Merelis
 */
public class DelimitedReader implements ObjectReader {

    protected char delimiter = ';';
    private String recordInitializator;
    private boolean removeRecordInitializator = true;
    private Character escape;

    private final ObjectMapperFactory mapperFactory = new ObjectMapperFactory(FormatType.DELIMITED);

    public ObjectMapperFactory getObjectMapperFactory() {
        return mapperFactory;
    }

    @Override
    public <T> T read(InputStream src, Class<T> clazz) {
        return read(src, clazz, null);

    }

    @Override
    public <T> T read(InputStream src, Class<T> clazz, String recordGroupId) {

        try {
            getObjectMapperFactory().create(clazz, recordGroupId);
            if (getObjectMapperFactory().getIdsMap().isEmpty()) {
                throw new UnsupportedOperationException("Até o momento, somente leitura de classes mapeadas com ID são suportadas");
            }
            return unmarshal(src, clazz);
        } catch (Exception ex) {
            Logger.getLogger(DelimitedReader.class.getName()).log(Level.SEVERE, null, ex);
            throw new RuntimeException(ex);
        }
    }

    public char getDelimiter() {
        return delimiter;
    }

    public void setDelimiter(char delimiter) {
        this.delimiter = delimiter;
    }

    public Character getEscape() {
        return escape;
    }

    public void setEscape(Character escape) {
        this.escape = escape;
    }

    public ObjectMapperFactory getMapperFactory() {
        return mapperFactory;
    }

    public String getRecordInitializator() {
        return recordInitializator;
    }

    public void setRecordInitializator(String recordInitializator) {
        this.recordInitializator = recordInitializator;
    }

    public boolean isRemoveRecordInitializator() {
        return removeRecordInitializator;
    }

    public void setRemoveRecordInitializator(boolean removeRecordInitializator) {
        this.removeRecordInitializator = removeRecordInitializator;
    }

    private <T> T unmarshal(InputStream src, Class<T> clazz) throws Exception {
        //TODO: Read byte by byte?

        T product;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(src))) {

            if (Collection.class.isAssignableFrom(clazz)) {
                readLine(reader);
                if (currentRecord == null && nextRecord != null) {
                    readLine(reader);
                }
                if (currentRecord == null) {
                    return null;
                }
                product = clazz.newInstance();
                while (currentRecord != null) {
                    Object o = processRecord(reader);
                    if (o != null) {
                        ((Collection) product).add(o);
                    }
                    readLine(reader);
                }
                return product;
            } else {
                readLine(reader);
                if (nextRecord == null) {
                    return null;
                }

                ObjectMapper om = getObjectMapperFactory().getParsers().get(clazz);
                ObjectMapper omm = getObjectMapperFactory().getIdsMap().get(nextRecord[0]);
                if (om.getRootClass().equals(omm.getRootClass())) {
                    readLine(reader);//posiciona
                }

                product = clazz.newInstance();
                List<FieldImpl> mappedFields = om.getMappedFields();

                int i = 0;
                for (FieldImpl f : mappedFields) {
                    if (!f.getConstantValue().isEmpty()) {
                        i++;
                        continue;
                    }

                    if (!f.isCollection() && !f.isNestedObject() && f.getTypeHandlerImpl() != null) {
                        Object value = f.getTypeHandlerImpl().parse(currentRecord[i]);
                        setValue(product, value, f);
                    } else if (f.isCollection()) {
                        if (nextRecord != null) {
                            //se o proximo registro é um objeto desta collection                
                            String nextId = nextRecord[0];
                            ObjectMapper mc = getObjectMapperFactory().getIdsMap().get(nextId);
                            if (mc.getRootClass().equals(f.getClassType())) {
                                Collection c = getCollection(product, f);
                                do {
                                    readLine(reader);
                                    Object r = processRecord(reader);
                                    if (r != null) {
                                        c.add(r);
                                    }
                                } while (nextRecord != null && nextRecord[0].equals(nextId));
                            }
                        }

                    } else if (f.isNestedObject() && nextRecord != null) {
                        //se o proximo registro é um objeto deste mesmo tipo               
                        String nextId = nextRecord[0];
                        ObjectMapper mc = getObjectMapperFactory().getIdsMap().get(nextId);
                        if (mc.getRootClass().equals(f.getClassType())) {
                            readLine(reader);
                            Object r = processRecord(reader);
                            setValue(product, r, f);
                        }
                    }

                    i++;
                }

                return product;
            }

        }
    }

    private String[] currentRecord;
    private String[] nextRecord;

    private void readLine(BufferedReader reader) throws Exception {
        currentRecord = nextRecord;
        nextRecord = getNextRecord(reader);
    }

    //TODO: escape to regex
    private String getDelimiterForRegex() {
        switch (delimiter) {
            case '|':
                return "\\|";
            case '\\':
                return "\\\\";
        }
        return String.valueOf(delimiter);
    }

    private String getEscapeForRegex() {
        switch (escape) {
            case '|':
                return "\\|";
            case '\\':
                return "\\\\";
        }
        return String.valueOf(escape);
    }

    private String[] getNextRecord(BufferedReader reader) throws Exception {
        String line = null;
        while (true) {
            line = reader.readLine();
            if (line == null) {
                return null;
            }
            line = line.trim();
            if (!line.isEmpty()) {
                break;
            }
        }

        if (recordInitializator != null && removeRecordInitializator) {
            if (line.startsWith(recordInitializator)) {
                line = line.substring(recordInitializator.length());
            }
        }

        String splitExp;

        String d = getDelimiterForRegex();
        if (escape != null) {
            splitExp = "[^" + getEscapeForRegex() + "]" + d;
        } else {
            splitExp = "" + d;
        }
        String[] values = line.split(splitExp);

        if (escape != null) {
            String _esc = String.valueOf(escape);
            String esc2esc = "" + escape + escape;
            String _delimiter = String.valueOf(delimiter);
            String escDelimiter = escape + _delimiter;

            for (int i = 0; i < values.length; i++) {
                values[i] = values[i].replace(esc2esc, _esc);
                values[i] = values[i].replace(escDelimiter, _delimiter);
            }
        }

        return values;
    }

    private Object processRecord(BufferedReader reader) throws Exception {

        if (currentRecord == null) {
            return null;
        }

        ObjectMapper om = getObjectMapperFactory().getIdsMap().get(currentRecord[0]);

        Object product = om.getRootClass().newInstance();

        List<FieldImpl> mappedFields = om.getMappedFields();

        int i = 0;
        for (FieldImpl f : mappedFields) {
            if (!f.getConstantValue().isEmpty()) {
                i++;
                continue;
            }

            if (!f.isCollection() && !f.isNestedObject() && f.getTypeHandlerImpl() != null) {
                Object value = f.getTypeHandlerImpl().parse(currentRecord[i]);
                setValue(product, value, f);
            } else if (f.isCollection()) {
                if (nextRecord != null) {
                    //se o proximo registro é um objeto desta collection                
                    String nextId = nextRecord[0];
                    ObjectMapper mc = getObjectMapperFactory().getIdsMap().get(nextId);
                    if (mc.getRootClass().equals(f.getClassType())) {
                        Collection c = getCollection(product, f);
                        do {
                            readLine(reader);
                            Object r = processRecord(reader);
                            if (r != null) {
                                c.add(r);
                            }
                        } while (nextRecord != null && nextRecord[0].equals(nextId));
                    }
                }

            } else if (f.isNestedObject() && nextRecord != null) {
                //se o proximo registro é um objeto deste mesmo tipo               
                String nextId = nextRecord[0];
                ObjectMapper mc = getObjectMapperFactory().getIdsMap().get(nextId);
                if (mc.getRootClass().equals(f.getClassType())) {
                    readLine(reader);
                    Object r = processRecord(reader);
                    setValue(product, r, f);
                }
            }

            i++;
        }
        return product;
    }

    private Collection getCollection(final Object obj, final FieldImpl f) {
        Object o = null;
        if (f.getGetterMethod() != null) {
            //PROPERTY
            o = AccessController.doPrivileged(new PrivilegedAction() {
                @Override
                public Object run() {
                    boolean wasAccessible = f.getGetterMethod().isAccessible();
                    try {
                        f.getGetterMethod().setAccessible(true);
                        Object c = f.getGetterMethod().invoke(obj);
                        if (c == null) {
                            if (List.class.isAssignableFrom(f.getCollectionType())) {
                                c = new LinkedList();
                            } else if (Set.class.isAssignableFrom(f.getCollectionType())) {
                                c = new LinkedHashSet();
                            }
                            f.getSetterMethod().setAccessible(true);
                            f.getSetterMethod().invoke(obj, c);
                        }
                        return c;
                    } catch (Exception ex) {
                        throw new IllegalStateException("Cannot invoke method for collection", ex);
                    } finally {
                        f.getGetterMethod().setAccessible(wasAccessible);
                        f.getSetterMethod().setAccessible(wasAccessible);
                    }
                }
            });
        } else {
            try {
                //FIELD
                final java.lang.reflect.Field declaredField;

                declaredField = obj.getClass().getDeclaredField(f.getName());
                o = AccessController.doPrivileged(new PrivilegedAction() {
                    @Override
                    public Object run() {
                        boolean wasAccessible = declaredField.isAccessible();
                        try {
                            declaredField.setAccessible(true);
                            Object c = declaredField.get(obj);

                            if (c == null) {
                                if (List.class.isAssignableFrom(f.getCollectionType())) {
                                    c = new LinkedList();
                                } else if (Set.class.isAssignableFrom(f.getCollectionType())) {
                                    c = new LinkedHashSet();
                                }
                                declaredField.set(obj, c);
                            }
                            return c;
                        } catch (Exception ex) {
                            throw new IllegalStateException("Cannot access set field", ex);
                        } finally {
                            declaredField.setAccessible(wasAccessible);
                        }

                    }
                });
            } catch (NoSuchFieldException ex) {
                Logger.getLogger(DelimitedReader.class.getName()).log(Level.SEVERE, null, ex);
            } catch (SecurityException ex) {
                Logger.getLogger(DelimitedReader.class.getName()).log(Level.SEVERE, null, ex);
            }
        }

        return (Collection) o;
    }

    private void setValue(final Object obj, final Object fieldValue, final FieldImpl f) {
        if (f.getSetterMethod() != null) {
            //PROPERTY
            AccessController.doPrivileged(new PrivilegedAction() {
                @Override
                public Object run() {
                    boolean wasAccessible = f.getSetterMethod().isAccessible();
                    try {
                        f.getSetterMethod().setAccessible(true);
                        return f.getSetterMethod().invoke(obj, fieldValue);
                    } catch (Exception ex) {
                        throw new IllegalStateException("Cannot invoke method set", ex);
                    } finally {
                        f.getSetterMethod().setAccessible(wasAccessible);
                    }
                }
            });
        } else {
            try {
                //FIELD
                final java.lang.reflect.Field declaredField;

                declaredField = obj.getClass().getDeclaredField(f.getName());
                AccessController.doPrivileged(new PrivilegedAction() {
                    @Override
                    public Object run() {
                        boolean wasAccessible = declaredField.isAccessible();
                        try {
                            declaredField.setAccessible(true);
                            declaredField.set(obj, fieldValue);
                        } catch (Exception ex) {
                            throw new IllegalStateException("Cannot access set field", ex);
                        } finally {
                            declaredField.setAccessible(wasAccessible);
                        }
                        return null;
                    }
                });
            } catch (NoSuchFieldException ex) {
                Logger.getLogger(DelimitedReader.class.getName()).log(Level.SEVERE, null, ex);
            } catch (SecurityException ex) {
                Logger.getLogger(DelimitedReader.class.getName()).log(Level.SEVERE, null, ex);
            }
        }
    }
}
