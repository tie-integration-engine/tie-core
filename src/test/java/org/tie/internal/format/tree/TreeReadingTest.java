package org.tie.internal.format.tree;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.tie.FileErrorType;
import org.tie.config.FieldDefinition;
import org.tie.config.FileFormat;
import org.tie.config.JsonOptions;
import org.tie.config.Layout;
import org.tie.config.XmlOptions;
import org.tie.internal.format.FileFailure;
import org.tie.internal.format.NestedRecords;
import org.tie.internal.format.ReadRecord;
import org.tie.internal.format.Reading;

class TreeReadingTest {

    private static FileFailure failure(Runnable reading) {
        try {
            reading.run();
        } catch (FileFailure e) {
            return e;
        }
        throw new AssertionError("expected a FileFailure");
    }

    @Nested
    class Json {

        private final Layout passThrough = Layout.builder("r").allowedFormats(FileFormat.JSON).build();

        private Reading json(String content, Layout root, Layout... children) {
            return Reading.text(FileFormat.JSON, content, root, children);
        }

        private final Layout firstArray = Layout.builder("r").allowedFormats(FileFormat.JSON)
                .json(JsonOptions.builder().firstArray(true).build()).build();

        @Test
        @DisplayName("AC-266 firstArray reads the first array property of a root object, skipping the ones before")
        void firstArrayOfTheRootObject() {
            assertThat(json("{\"cabecera\":{\"v\":1},\"Empleados\":[{\"nif\":\"1\"},{\"nif\":\"2\"}]}", firstArray).raw())
                    .containsExactly(Map.of("nif", "1"), Map.of("nif", "2"));
        }

        @Test
        @DisplayName("AC-267 a root array reads the same with and without firstArray")
        void rootArrayWithFirstArray() {
            String content = "[{\"nif\":\"1\"}]";

            assertThat(json(content, firstArray).raw()).isEqualTo(json(content, passThrough).raw())
                    .containsExactly(Map.of("nif", "1"));
        }

        @Test
        @DisplayName("AC-268 a root object without arrays is STRUCTURAL, with and without firstArray")
        void rootObjectWithoutArrays() {
            for (String content : new String[] {"{\"cabecera\":{\"v\":1}}", "{}"}) {
                FileFailure failure = failure(() -> json(content, firstArray));
                assertThat(failure.type()).isEqualTo(FileErrorType.STRUCTURAL);
                assertThat(failure).hasMessageContaining("expected an array property in the root JSON object");
            }
            assertThat(failure(() -> json("{\"cabecera\":{\"v\":1}}", passThrough)))
                    .hasMessageContaining("expected a JSON array of records at the root");
        }

        @Test
        @DisplayName("AC-269 only the first array is read; a later array is not")
        void onlyTheFirstArray() {
            assertThat(json("{\"a\":[{\"x\":\"1\"}],\"b\":[{\"x\":\"2\"}]}", firstArray).raw())
                    .containsExactly(Map.of("x", "1"));
        }

        @Test
        @DisplayName("AC-270 firstArray only looks at top-level properties, not at arrays nested in objects")
        void onlyTopLevel() {
            assertThat(json("{\"meta\":{\"lineas\":[{\"x\":\"0\"}]},\"datos\":[{\"x\":\"1\"}]}", firstArray).raw())
                    .containsExactly(Map.of("x", "1"));
        }

        @Test
        @DisplayName("AC-271 firstArray: an array of scalars or a scalar root is STRUCTURAL; an empty array has no records")
        void firstArrayEdges() {
            FileFailure scalars = failure(() -> json("{\"datos\":[\"a\",\"b\"]}", firstArray));
            assertThat(scalars.type()).isEqualTo(FileErrorType.STRUCTURAL);
            assertThat(scalars).hasMessageContaining("every record must be a JSON object");
            assertThat(failure(() -> json("\"texto\"", firstArray)).type()).isEqualTo(FileErrorType.STRUCTURAL);
            assertThat(json("{\"datos\":[]}", firstArray).records).isEmpty();
        }

        @Test
        @DisplayName("AC-39 nested objects are flattened with dotted keys")
        void flattening() {
            assertThat(json("[{\"a\":1,\"b\":{\"c\":2}}]", passThrough).raw())
                    .containsExactly(Map.of("a", "1", "b.c", "2"));
        }

        @Test
        @DisplayName("AC-30 a missing JSON key is an empty value")
        void missingKey() {
            Layout layout = Layout.builder("r").allowedFormats(FileFormat.JSON)
                    .field(FieldDefinition.builder("a")).field(FieldDefinition.builder("b").defaultValue("d")).build();

            assertThat(json("[{\"a\":\"x\"}]", layout).typed()).containsExactly(Map.of("a", "x", "b", "d"));
        }

        @Test
        @DisplayName("AC-157 a field reads a path through column or through its own dotted name")
        void paths() {
            Layout layout = Layout.builder("r").allowedFormats(FileFormat.JSON)
                    .field(FieldDefinition.builder("n").column("cliente.nif"))
                    .field(FieldDefinition.builder("cliente.nif"))
                    .build();

            assertThat(json("[{\"cliente\":{\"nif\":\"1\"}}]", layout).typed())
                    .containsExactly(Map.of("n", "1", "cliente.nif", "1"));
        }

        @Test
        @DisplayName("AC-161 an array that is not a nested layout is a structural error")
        void undeclaredArray() {
            FileFailure failure = failure(() -> json("[{\"id\":1,\"tags\":[1,2]}]", passThrough));

            assertThat(failure.type()).isEqualTo(FileErrorType.STRUCTURAL);
            assertThat(failure).hasMessage("array 'tags' is not declared as a nested layout");
        }

        @Test
        @DisplayName("AC-79 children [] / absent / null: declared true / false / false, no children")
        void childrenDeclared() {
            Layout child = Layout.builder("c").parent("r", "children").build();
            Reading reading = json("[{\"id\":1,\"children\":[]},{\"id\":2},{\"id\":3,\"children\":null}]",
                    passThrough, child);

            assertThat(reading.records).extracting(record -> record.children().getFirst().declared())
                    .containsExactly(true, false, false);
            assertThat(reading.records).allSatisfy(record ->
                    assertThat(record.children().getFirst().records()).isEmpty());
        }

        @Test
        void childrenAreReadInOrderWithTheirOwnLayoutAndGrandchildren() {
            Layout order = Layout.builder("order").allowedFormats(FileFormat.JSON).json(JsonOptions.builder().recordProperty("orders").build())
                    .field(FieldDefinition.builder("id")).build();
            Layout line = Layout.builder("line").parent("order", "lines").field(FieldDefinition.builder("sku")).build();
            Layout note = Layout.builder("note").parent("line", "detail.notes").build();

            Reading reading = json("""
                    {"meta": {"skip": [1, 2]},
                     "orders": [{"lines": [{"sku": "A", "detail": {"notes": [{"t": "n1"}]}}, {"sku": "B"}], "id": 7}],
                     "after": true}
                    """, order, line, note);

            ReadRecord parent = reading.records.getFirst();
            assertThat(parent.input().fieldInputs()).containsExactly(entry("id", "7"));
            NestedRecords lines = parent.children().getFirst();
            assertThat(lines.layout()).isEqualTo(line);
            assertThat(lines.records()).extracting(r -> r.input().fieldInputs().get("sku")).containsExactly("A", "B");
            assertThat(lines.records().getFirst().children().getFirst().records())
                    .extracting(r -> r.input().rawValues()).containsExactly(Map.of("t", "n1"));
            assertThat(lines.records().get(1).children().getFirst().declared()).isFalse();
        }

        @Test
        void scalarsKeepTheirText() {
            assertThat(json("[{\"i\":12,\"d\":1.50,\"big\":123456789012345678901234,\"b\":false,\"s\":\" x \",\"n\":null}]",
                    passThrough).raw())
                    .containsExactly(Map.of("i", "12", "d", "1.50", "big", "123456789012345678901234", "b", "false",
                            "s", " x "));
        }

        @Test
        void structuralErrors() {
            Layout byProperty = Layout.builder("r").allowedFormats(FileFormat.JSON).json(JsonOptions.builder().recordProperty("rows").build()).build();

            assertThat(failure(() -> json("{\"a\":1}", passThrough)))
                    .hasMessage("expected a JSON array of records at the root (line 1, column 2)");
            assertThat(failure(() -> json("[1]", passThrough))).hasMessageStartingWith("every record must be a JSON object");
            assertThat(failure(() -> json("{\"other\":[]}", byProperty))).hasMessage("property 'rows' not found");
            assertThat(failure(() -> json("[{\"a\":1},{\"a\":", passThrough)).type()).isEqualTo(FileErrorType.STRUCTURAL);
            assertThat(failure(() -> json("[{\"a\":1,\"a\":2}]", passThrough))).hasMessageContaining("Duplicate");
            assertThat(failure(() -> Reading.bytes(FileFormat.JSON, new byte[]{'[', '{', '"', 'a', '"', ':', '"',
                    (byte) 0xFF, '"', '}', ']'}, passThrough)).type()).isEqualTo(FileErrorType.ENCODING);
        }

        @Test
        void recordsBeforeAnErrorAreReturned() {
            Layout layout = passThrough;
            java.util.List<String> seen = new java.util.ArrayList<>();

            assertThat(failure(() -> {
                try (var reader = org.tie.internal.format.RecordReaders.open(FileFormat.JSON,
                        new java.io.ByteArrayInputStream("[{\"a\":1},{\"a\":2},{\"a\":".getBytes(StandardCharsets.UTF_8)),
                        layout, java.util.List.of(layout), w -> {
                        }, "f.json")) {
                    ReadRecord record;
                    while ((record = reader.next()) != null) {
                        seen.add(record.input().rawValues().get("a"));
                    }
                }
            }).type()).isEqualTo(FileErrorType.STRUCTURAL);
            assertThat(seen).containsExactly("1", "2");
        }
    }

    @Nested
    class Xml {

        private final Layout passThrough = Layout.builder("r").allowedFormats(FileFormat.XML)
                .xml(XmlOptions.of("rec")).build();

        private Reading xml(String content, Layout root, Layout... children) {
            return Reading.text(FileFormat.XML, content, root, children);
        }

        private final Layout anyName = Layout.builder("r").allowedFormats(FileFormat.XML).build();

        @Test
        @DisplayName("AC-261 without recordElement every child of the root is a record")
        void childrenOfTheRoot() {
            Reading reading = xml("<empleados><empleado><nif>1</nif></empleado><empleado><nif>2</nif></empleado></empleados>",
                    anyName);

            assertThat(reading.raw()).containsExactly(Map.of("nif", "1"), Map.of("nif", "2"));
        }

        @Test
        @DisplayName("AC-262 without recordElement sibling records may have different names")
        void siblingsWithDifferentNames() {
            assertThat(xml("<raiz><a><v>1</v></a><b><v>2</v></b></raiz>", anyName).raw())
                    .containsExactly(Map.of("v", "1"), Map.of("v", "2"));
        }

        @Test
        @DisplayName("AC-264 text, comments, processing instructions and attributes of the root are ignored; an empty root has no records")
        void rootContentIgnored() {
            assertThat(xml("<raiz a=\"1\">texto<!-- c --><?pi x?><r><v>1</v></r>más</raiz>", anyName).raw())
                    .containsExactly(Map.of("v", "1"));
            assertThat(xml("<raiz/>", anyName).records).isEmpty();
        }

        @Test
        @DisplayName("AC-35 a DOCTYPE fails the file and its entities are never resolved")
        void doctype() {
            String xxe = """
                    <?xml version="1.0"?>
                    <!DOCTYPE records [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                    <records><rec><name>&xxe;</name></rec></records>
                    """;

            FileFailure failure = failure(() -> xml(xxe, passThrough));

            assertThat(failure.type()).isEqualTo(FileErrorType.UNSAFE_CONTENT);
            assertThat(failure).hasMessageNotContaining("root:");
            assertThat(failure(() -> xml("<records><rec><name>&undeclared;</name></rec></records>", passThrough)).type())
                    .isEqualTo(FileErrorType.STRUCTURAL);
        }

        @Test
        @DisplayName("AC-80 a container with a single child is one child record")
        void singleChild() {
            Layout child = Layout.builder("c").parent("r", "children").build();

            Reading reading = xml("<records><rec><id>1</id><children><child><v>a</v></child></children></rec></records>",
                    passThrough, child);

            NestedRecords children = reading.records.getFirst().children().getFirst();
            assertThat(children.declared()).isTrue();
            assertThat(children.records()).extracting(r -> r.input().rawValues()).containsExactly(Map.of("v", "a"));
            assertThat(reading.raw()).containsExactly(Map.of("id", "1"));
        }

        @Test
        @DisplayName("AC-158 attributes are @name")
        void attributes() {
            Layout layout = Layout.builder("r").allowedFormats(FileFormat.XML).xml(XmlOptions.of("rec"))
                    .field(FieldDefinition.builder("id").column("@id"))
                    .field(FieldDefinition.builder("name"))
                    .build();

            assertThat(xml("<rec id=\"7\"><name>x</name></rec>", layout).typed()).containsExactly(Map.of("id", "7", "name", "x"));
        }

        @Test
        @DisplayName("AC-159 namespaces are ignored: elements match by local name")
        void namespaces() {
            Layout layout = Layout.builder("r").allowedFormats(FileFormat.XML).xml(XmlOptions.of("rec"))
                    .field(FieldDefinition.builder("name")).build();

            assertThat(xml("<a:rec xmlns:a=\"urn:x\"><a:name>x</a:name></a:rec>", layout).typed())
                    .containsExactly(Map.of("name", "x"));
        }

        @Test
        @DisplayName("AC-160 a repeated element that is not a nested layout is a structural error")
        void repeatedElement() {
            FileFailure failure = failure(() -> xml("<rec><phone>1</phone><phone>2</phone></rec>", passThrough));

            assertThat(failure.type()).isEqualTo(FileErrorType.STRUCTURAL);
            assertThat(failure).hasMessage("element 'phone' is repeated and is not declared as a nested layout");
        }

        @Test
        void nestedElementsAttributesTextAndEntities() {
            Reading reading = xml("""
                    <records generated="today">
                      <rec id="1">
                        ignored text
                        <customer type="vip"><name> Ana &amp; Co </name><address><city>Madrid</city></address></customer>
                        <amount currency="EUR">12.50</amount>
                        <empty/>
                        <note><![CDATA[a <b> c]]></note>
                      </rec>
                      <rec id="2"/>
                    </records>
                    """, passThrough);

            assertThat(reading.raw()).containsExactly(
                    Map.of("@id", "1", "customer.@type", "vip", "customer.name", " Ana & Co ",
                            "customer.address.city", "Madrid", "amount.@currency", "EUR", "amount", "12.50",
                            "empty", "", "note", "a <b> c"),
                    Map.of("@id", "2"));
        }

        @Test
        void containerAbsentIsNotDeclaredAndEmptyContainerIs() {
            Layout child = Layout.builder("c").parent("r", "items").build();

            Reading reading = xml("<records><rec><items/></rec><rec/></records>", passThrough, child);

            assertThat(reading.records).extracting(r -> r.children().getFirst().declared()).containsExactly(true, false);
        }

        @Test
        void malformedXmlIsStructural() {
            FileFailure failure = failure(() -> xml("<records><rec><a>1</b></rec></records>", passThrough));

            assertThat(failure.type()).isEqualTo(FileErrorType.STRUCTURAL);
            assertThat(failure).hasMessageStartingWith("invalid XML:").hasMessageContaining("line 1");
        }

        @Test
        void encodingFromOptions() {
            Layout latin1 = Layout.builder("r").allowedFormats(FileFormat.XML)
                    .xml(new XmlOptions("rec", null, StandardCharsets.ISO_8859_1)).build();

            assertThat(Reading.text(FileFormat.XML, "<rec><n>José</n></rec>", StandardCharsets.ISO_8859_1, latin1).raw())
                    .containsExactly(Map.of("n", "José"));
        }
    }
}
