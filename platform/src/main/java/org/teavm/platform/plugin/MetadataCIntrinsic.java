/*
 *  Copyright 2018 Alexey Andreev.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.teavm.platform.plugin;

import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import org.teavm.ast.InvocationExpr;
import org.teavm.backend.c.generate.CodeWriter;
import org.teavm.backend.c.intrinsic.Intrinsic;
import org.teavm.backend.c.intrinsic.IntrinsicContext;
import org.teavm.common.ServiceRepository;
import org.teavm.model.CallLocation;
import org.teavm.model.ClassReaderSource;
import org.teavm.model.MethodReader;
import org.teavm.model.MethodReference;
import org.teavm.platform.metadata.MetadataGenerator;
import org.teavm.platform.metadata.MetadataProvider;
import org.teavm.platform.metadata.Resource;

public class MetadataCIntrinsic implements Intrinsic {
    private ClassReaderSource classSource;
    private ClassLoader classLoader;
    private Set<String> writtenStructures = new HashSet<>();
    private Set<MethodReference> writtenInitializers = new HashSet<>();
    private CodeWriter structuresWriter;
    private CodeWriter staticFieldInitWriter;
    private DefaultMetadataGeneratorContext metadataContext;

    public MetadataCIntrinsic(ClassReaderSource classSource, ClassLoader classLoader,
            ServiceRepository services, Properties properties, CodeWriter structuresWriter,
            CodeWriter staticFieldInitWriter) {
        this.classSource = classSource;
        this.classLoader = classLoader;
        this.structuresWriter = structuresWriter;
        this.staticFieldInitWriter = staticFieldInitWriter;
        metadataContext = new DefaultMetadataGeneratorContext(classSource, classLoader, properties, services);
    }

    @Override
    public boolean canHandle(MethodReference methodReference) {
        MethodReader method = classSource.resolve(methodReference);
        if (method == null) {
            return false;
        }

        return method.getAnnotations().get(MetadataProvider.class.getName()) != null;
    }

    @Override
    public void apply(IntrinsicContext context, InvocationExpr invocation) {
        writeInitializer(context, invocation);
        context.writer().print(context.names().forMethod(invocation.getMethod()));
    }

    private void writeInitializer(IntrinsicContext context, InvocationExpr invocation) {
        MethodReference methodReference = invocation.getMethod();
        if (!writtenInitializers.add(methodReference)) {
            return;
        }

        MethodReader method = classSource.resolve(methodReference);
        MetadataGenerator generator = MetadataUtils.createMetadataGenerator(classLoader, method,
                new CallLocation(invocation.getMethod()), context.getDiagnotics());

        String variableName = context.names().forMethod(methodReference);
        staticFieldInitWriter.print("static ").printType(method.getResultType()).print(" ")
                .print(variableName).print(" = ");
        if (generator == null) {
            staticFieldInitWriter.print("NULL");
        } else {
            Resource resource = generator.generateMetadata(metadataContext, invocation.getMethod());
            writeValue(context, resource);
        }
        staticFieldInitWriter.println(";");
    }

    private void writeValue(IntrinsicContext context, Object value) {
        if (value instanceof String) {
            int stringIndex = context.getStringPool().getStringIndex((String) value);
            staticFieldInitWriter.print("(stringPool + " + stringIndex + ")");
        } else if (value instanceof ResourceTypeDescriptorProvider && value instanceof Resource) {
            writeResource(context, (ResourceTypeDescriptorProvider) value);
        } else {
            throw new IllegalArgumentException("Don't know how to write resource: " + value);
        }
    }

    private void writeResource(IntrinsicContext context, ResourceTypeDescriptorProvider resourceType) {
        writeResourceStructure(context, resourceType.getDescriptor());

        String structureName = context.names().forClass(resourceType.getDescriptor().getRootInterface().getName());
        Object[] propertyValues = resourceType.getValues();
        staticFieldInitWriter.print("&(" + structureName + ") {").indent();
        boolean first = true;
        for (String propertyName : resourceType.getDescriptor().getPropertyTypes().keySet()) {
            if (!first) {
                staticFieldInitWriter.print(",");
            }
            first = false;
            staticFieldInitWriter.println().print(".").print(propertyName).print(" = ");
            int index = resourceType.getPropertyIndex(propertyName);
            Object propertyValue = propertyValues[index];
            writeValue(context, propertyValue);
        }
        staticFieldInitWriter.println().outdent().print("}");
    }

    private void writeResourceStructure(IntrinsicContext context, ResourceTypeDescriptor structure) {
        String className = structure.getRootInterface().getName();
        if (!writtenStructures.add(className)) {
            return;
        }

        for (Class<?> propertyType : structure.getPropertyTypes().values()) {
            if (Resource.class.isAssignableFrom(propertyType)) {
                ResourceTypeDescriptor propertyStructure = metadataContext.getTypeDescriptor(
                        propertyType.asSubclass(Resource.class));
                writeResourceStructure(context, propertyStructure);
            }
        }

        String structureName = context.names().forClass(className);
        structuresWriter.println("typedef struct " + structureName + " {").indent();

        for (String propertyName : structure.getPropertyTypes().keySet()) {
            Class<?> propertyType = structure.getPropertyTypes().get(propertyName);
            structuresWriter.println(typeToString(context, propertyType) + " " + propertyName + ";");
        }

        structuresWriter.outdent().println("} " + structureName + ";");
    }

    private String typeToString(IntrinsicContext context, Class<?> cls) {
        if (cls == boolean.class || cls == byte.class) {
            return "int8_t";
        } else if (cls == short.class || cls == char.class) {
            return "int16_t";
        } else if (cls == int.class) {
            return "int32_t";
        } else if (cls == float.class) {
            return "float";
        } else if (cls == long.class) {
            return "int64_t";
        } else if (cls == double.class) {
            return "double";
        } else if (Resource.class.isAssignableFrom(cls)) {
            return "&" + context.names().forClass(cls.getName());
        } else if (cls == String.class) {
            return "JavaObject*";
        } else {
            throw new IllegalArgumentException("Don't know how to write resource type " + cls);
        }
    }

    static class MethodContext {
        String baseName;
        int suffix;

        MethodContext(String baseName) {
            this.baseName = baseName;
        }
    }
}
