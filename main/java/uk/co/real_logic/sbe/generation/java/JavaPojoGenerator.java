/*
 * Copyright 2013 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package uk.co.real_logic.sbe.generation.java;

import uk.co.real_logic.sbe.generation.CodeGenerator;
import uk.co.real_logic.sbe.generation.OutputManager;
import uk.co.real_logic.sbe.ir.Encoding;
import uk.co.real_logic.sbe.ir.Ir;
import uk.co.real_logic.sbe.ir.Signal;
import uk.co.real_logic.sbe.ir.Token;
import uk.co.real_logic.sbe.util.Verify;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

import static uk.co.real_logic.sbe.generation.java.JavaUtil.formatClassName;
import static uk.co.real_logic.sbe.generation.java.JavaUtil.formatPropertyName;
import static uk.co.real_logic.sbe.generation.java.JavaUtil.javaTypeName;

public class JavaPojoGenerator implements CodeGenerator
{
    private static final String BASE_INDENT = "";
    private static final String INDENT = "    ";
    public static final String MOCK = "Mock";

    private final Ir ir;
    private final OutputManager outputManager;

    public JavaPojoGenerator(final Ir ir, final OutputManager outputManager)
        throws IOException
    {
        Verify.notNull(ir, "ir");
        Verify.notNull(outputManager, "outputManager");

        this.ir = ir;
        this.outputManager = outputManager;
    }

    public void generateMessageHeaderStub() throws IOException
    {
        try (final Writer out = outputManager.createOutput(MESSAGE_HEADER_TYPE + MOCK))
        {
            final List<Token> tokens = ir.headerStructure().tokens();
            out.append(generateFileHeader(ir.applicableNamespace()));
            out.append(generateClassDeclaration(MESSAGE_HEADER_TYPE));

            out.append(generatePrimitivePropertyEncodings(
                MESSAGE_HEADER_TYPE, tokens.subList(1, tokens.size() - 1), BASE_INDENT));

            out.append("}\n");
        }
    }

    public void generateTypeStubs() throws IOException
    {
        for (final List<Token> tokens : ir.types())
        {
            switch (tokens.get(0).signal())
            {
                case BEGIN_ENUM:
                    break;
                case BEGIN_SET:
                    generateBitSet(tokens);
                    break;
                case BEGIN_COMPOSITE:
                    generateComposite(tokens);
                    break;
            }
        }
    }

    private void generateBitSet(final List<Token> tokens) throws IOException
    {
        final String bitSetName = formatClassName(tokens.get(0).name());

        try (final Writer out = outputManager.createOutput(bitSetName + MOCK))
        {
            out.append(generateFileHeader(ir.applicableNamespace()));
            out.append(generateClassDeclaration(bitSetName));
            out.append(generateChoices(bitSetName, tokens.subList(1, tokens.size() - 1)));

            out.append("}\n");
        }
    }

    private CharSequence generateChoices(final String bitSetClassName, final List<Token> tokens)
    {
        final StringBuilder sb = new StringBuilder();

        for (final Token token : tokens)
        {
            if (token.signal() == Signal.CHOICE)
            {
                final String choiceName = token.name();
                sb.append(String.format(
                        "\n" +
                                "    private boolean %s;\n" +
                                "    public boolean %s()\n" +
                                "    {\n" +
                                "        return %s;\n" +
                                "    }\n\n" +
                                "    public %s %s(final boolean value)\n" +
                                "    {\n" +
                                "        %s = value;\n" +
                                "        return this;\n" +
                                "    }\n",
                        choiceName, choiceName, choiceName,
                        bitSetClassName,
                        choiceName, choiceName
                ));
            }
        }

        return sb;
    }

    public void generate() throws IOException
    {
        generateMessageHeaderStub();
        generateTypeStubs();

        for (final List<Token> tokens : ir.messages())
        {
            final Token msgToken = tokens.get(0);
            final String className = formatClassName(msgToken.name());

            try (final Writer out = outputManager.createOutput(className + MOCK))
            {
                out.append(generateFileHeader(ir.applicableNamespace()));
                out.append(generateClassDeclaration(className));

                final List<Token> messageBody = tokens.subList(1, tokens.size() - 1);
                int offset = 0;

                final List<Token> rootFields = new ArrayList<>();
                collectRootFields(messageBody, offset, rootFields);
                out.append(generateFields(className, rootFields, BASE_INDENT));

                final List<Token> groups = new ArrayList<>();
                collectGroups(messageBody, offset, groups);
                final StringBuilder sb = new StringBuilder();
                generateGroups(sb, className, groups, 0, BASE_INDENT);
                out.append(sb);
                out.append("}\n");
            }
        }
    }

    private int collectGroups(final List<Token> tokens, int index, final List<Token> groups)
    {
        for (int size = tokens.size(); index < size; index++)
        {
            final Token token = tokens.get(index);
            if (Signal.BEGIN_VAR_DATA == token.signal())
            {
                return index;
            }

            groups.add(token);
        }

        return index;
    }

    private int collectRootFields(final List<Token> tokens, int index, final List<Token> rootFields)
    {
        for (int size = tokens.size(); index < size; index++)
        {
            final Token token = tokens.get(index);
            if (Signal.BEGIN_GROUP == token.signal() ||
                Signal.END_GROUP == token.signal() ||
                Signal.BEGIN_VAR_DATA == token.signal())
            {
                return index;
            }

            rootFields.add(token);
        }

        return index;
    }

    private int generateGroups(
            final StringBuilder sb,
            final String parentMessageClassName,
            final List<Token> tokens,
            int index,
            final String indent)
    {
        for (int size = tokens.size(); index < size; index++)
        {
            if (tokens.get(index).signal() == Signal.BEGIN_GROUP)
            {
                final Token groupToken = tokens.get(index);
                final String groupName = groupToken.name();
                final String groupClassName = formatClassName(groupName);
                sb.append(generateSingleValueProperty(parentMessageClassName,
                        JavaUtil.toLowerFirstChar(groupName), groupClassName, indent));

                generateGroupClassHeader(sb, groupName, indent + INDENT);

                final List<Token> rootFields = new ArrayList<>();
                index = collectRootFields(tokens, ++index, rootFields);
                sb.append(generateFields(groupClassName, rootFields, indent + INDENT));

                if (tokens.get(index).signal() == Signal.BEGIN_GROUP)
                {
                    index = generateGroups(sb, parentMessageClassName, tokens, index, indent + INDENT);
                }

                sb.append(indent).append("    }\n");
            }
        }

        return index;
    }

    private void generateGroupClassHeader(
        final StringBuilder sb,
        final String groupName,
        final String indent)
    {
        sb.append(String.format(
            "\n" +
            indent + "public static class %1$sMock extends %1$s\n" +
            indent + "{\n" +
            indent + "    private Iterator iterator;\n" +
            indent + "    private int count;\n" +
            indent + "    public %1$sMock() { iterator = Collections.emptyIterator(); }\n" +
            indent + "    public %1$sMock(%1$s... items) {\n" +
            indent + "        iterator = Arrays.asList(items).iterator();\n" +
            indent + "        count = items.length;\n" +
            indent + "    }\n",
            formatClassName(groupName)
        ));

        sb.append(String.format(
            indent + "    public int count()\n" +
            indent + "    {\n" +
            indent + "        return count;\n" +
            indent + "    }\n\n" +
            indent + "    @Override\n" +
            indent + "    public java.util.Iterator<%s> iterator()\n" +
            indent + "    {\n" +
            indent + "        return iterator;\n" +
            indent + "    }\n\n" +
            indent + "    @Override\n" +
            indent + "    public void remove()\n" +
            indent + "    {\n" +
            indent + "        throw new UnsupportedOperationException();\n" +
            indent + "    }\n\n" +
            indent + "    @Override\n" +
            indent + "    public boolean hasNext()\n" +
            indent + "    {\n" +
            indent + "        return iterator.hasNext();\n" +
            indent + "    }\n\n",
            formatClassName(groupName)
        ));

        sb.append(String.format(
            indent + "    @Override\n" +
            indent + "    public %1$s next()\n" +
            indent + "    {\n" +
            indent + "        return (%1$s) iterator.next();\n" +
            indent + "    }\n",
            formatClassName(groupName)
        ));
    }

    private void generateComposite(final List<Token> tokens) throws IOException
    {
        final String compositeName = formatClassName(tokens.get(0).name());

        try (final Writer out = outputManager.createOutput(compositeName + MOCK))
        {
            out.append(generateFileHeader(ir.applicableNamespace()));
            out.append(generateClassDeclaration(compositeName));
            out.append(generatePrimitivePropertyEncodings(compositeName, tokens.subList(1, tokens.size() - 1), BASE_INDENT));

            out.append("}\n");
        }
    }

    private CharSequence generateFileHeader(final String packageName)
    {
        return String.format(
            "/* Generated SBE (Simple Binary Encoding) message codec */\n" +
            "package %s;\n\n" +
            "import java.util.Arrays;\n" +
            "import java.util.Collections;\n" +
            "import java.util.Iterator;\n" +
            "import java.util.List;\n" +
            "import uk.co.real_logic.sbe.codec.java.*;\n\n",
            packageName
        );
    }

    private CharSequence generateClassDeclaration(final String className)
    {
        return String.format(
            "public class %sMock extends %s\n" +
            "{\n",
            className, className
        );
    }

    private CharSequence generatePrimitivePropertyEncodings(
        final String containingClassName, final List<Token> tokens, final String indent)
    {
        final StringBuilder sb = new StringBuilder();

        for (final Token token : tokens)
        {
            if (token.signal() == Signal.ENCODING || token.signal() == Signal.BEGIN_GROUP)
            {
                sb.append(generatePrimitiveProperty(containingClassName, token.name(), token, indent));
            }
        }

        return sb;
    }

    private CharSequence generatePrimitiveProperty(
        final String containingClassName, final String propertyName, final Token token, final String indent)
    {
        final StringBuilder sb = new StringBuilder();
        if (Encoding.Presence.CONSTANT != token.encoding().presence())
        {
            sb.append(generatePrimitivePropertyMethods(containingClassName, propertyName, token, indent));
        }

        return sb;
    }

    private CharSequence generatePrimitivePropertyMethods(
        final String containingClassName, final String propertyName, final Token token, final String indent)
    {
        final int arrayLength = token.arrayLength();

        final Encoding encoding = token.encoding();
        final String javaTypeName = javaTypeName(encoding.primitiveType());

        if (arrayLength == 1)
        {
            return generateSingleValueProperty(containingClassName, propertyName, javaTypeName, indent);
        }
        else if (arrayLength > 1)
        {
            return generateSingleValueProperty(containingClassName, propertyName, javaTypeName+"[]", indent);
        }

        return "";
    }

    private CharSequence generateSingleValueProperty(
        final String containingClassName, final String propertyName, final String javaTypeName, final String indent)
    {
        final StringBuilder sb = new StringBuilder();

        sb.append(String.format(
                "\n" + indent + "    private %s %s;\n",
                javaTypeName,
                propertyName
        ));

        sb.append(String.format(
            "\n" +
            indent + "    public %s %s()\n" +
            indent + "    {\n" +
            indent + "        return %s;\n" +
            indent + "    }\n\n",
            javaTypeName,
            propertyName,
            propertyName
        ));

        sb.append(String.format(
            indent + "    public %sMock %s(final %s value)\n" +
            indent + "    {\n" +
            indent + "        %s = value;\n" +
            indent + "        return this;\n" +
            indent + "    }\n",
            formatClassName(containingClassName),
            propertyName,
            javaTypeName,
            propertyName
        ));

        return sb;
    }

    private CharSequence generateFields(final String containingClassName, final List<Token> tokens, final String indent)
    {
        final StringBuilder sb = new StringBuilder();

        for (int i = 0, size = tokens.size(); i < size; i++)
        {
            final Token signalToken = tokens.get(i);
            if (signalToken.signal() == Signal.BEGIN_FIELD)
            {
                final Token encodingToken = tokens.get(i + 1);
                final String propertyName = formatPropertyName(signalToken.name());

                switch (encodingToken.signal())
                {
                    case ENCODING:
                        sb.append(generatePrimitiveProperty(containingClassName, propertyName, encodingToken, indent));
                        break;

                    case BEGIN_COMPOSITE:
                    case BEGIN_SET:
                    case BEGIN_ENUM:
                        sb.append(generateEnumProperty(containingClassName, propertyName, encodingToken, indent));
                        break;
                }
            }
        }

        return sb;
    }

    private CharSequence generateEnumProperty(
            final String containingClassName, final String propertyName, final Token token, final String indent)
    {

        final String enumName = token.name();
        return generateSingleValueProperty(containingClassName, propertyName, enumName, indent);
    }
}
