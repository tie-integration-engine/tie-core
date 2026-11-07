package org.tie.internal.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.tie.internal.run.ImportHarness.csv;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.tie.CancellationToken;
import org.tie.ErrorCode;
import org.tie.FileErrorType;
import org.tie.FileResult;
import org.tie.FileStatus;
import org.tie.ImportResult;
import org.tie.LineError;
import org.tie.LineRejection;
import org.tie.ResultStatus;
import org.tie.TypedRecord;
import org.tie.config.ConsumerErrorPolicy;
import org.tie.config.DeliveryStrategy;
import org.tie.config.FieldDefinition;
import org.tie.config.FieldType;
import org.tie.config.FileFormat;
import org.tie.config.IntegrationDefinition;
import org.tie.config.Layout;
import org.tie.config.OnEmpty;
import org.tie.config.OnParentRejected;
import org.tie.config.RejectionThreshold;
import org.tie.config.TypeFormat;
import org.tie.config.Validations;
import org.tie.spi.Outcome;

class ImportDeliveryTest {

    @TempDir
    Path root;

    private ImportHarness h;

    @BeforeEach
    void setUp() {
        h = new ImportHarness(root);
    }

    private static FileResult only(ImportResult result) {
        assertThat(result.files()).hasSize(1);
        return result.files().getFirst();
    }

    /** A file of {@code total} lines whose INTEGER column has {@code invalid} values "x" at the end. */
    private void writeNumbers(String name, int total, int invalid) {
        StringBuilder content = new StringBuilder("value\n");
        for (int i = 1; i <= total; i++) {
            content.append(i > total - invalid ? "x" : String.valueOf(i)).append('\n');
        }
        h.write(name, content.toString());
    }

    private IntegrationDefinition.Builder numbers() {
        return h.importOf(csv("alta", "alta_{timestamp:yyyyMMddHHmmss}")
                .fields(List.of(FieldDefinition.builder("value").type(FieldType.INTEGER).build())));
    }

    @Nested
    class Thresholds {

        @Test
        @DisplayName("AC-66 the file fails when rejections exceed maxRejected")
        void maxRejected() {
            writeNumbers("alta_20261026120000.csv", 5, 2);
            writeNumbers("alta_20261027120000.csv", 5, 3);

            ImportResult result = h.run(numbers().rejectionThreshold(RejectionThreshold.maxRejected(2)));

            assertThat(result.files()).extracting(FileResult::status).containsExactly(FileStatus.SUCCESS, FileStatus.FAILED);
            assertThat(result.files().get(1).error().type()).isEqualTo(FileErrorType.THRESHOLD);
            assertThat(result.files().get(1).error().message()).startsWith("rejection threshold exceeded");
            assertThat(result.files().get(1).rejected()).isEqualTo(3);
        }

        @Test
        @DisplayName("AC-67 maxRejected = 0 fails on the first rejection")
        void zeroRejections() {
            writeNumbers("alta_20261026120000.csv", 5, 4);

            FileResult file = only(h.run(numbers().rejectionThreshold(RejectionThreshold.maxRejected(0))));

            assertThat(file.status()).isEqualTo(FileStatus.FAILED);
            assertThat(file.rejected()).isEqualTo(1);
            assertThat(file.deliveredUpToLine()).isEqualTo(2);
        }

        @Test
        @DisplayName("AC-68 VALIDATE_THEN_DELIVER with a percentage: 10 % passes, 11 % delivers nothing")
        void percentage() {
            writeNumbers("alta_20261026120000.csv", 100, 10);
            IntegrationDefinition.Builder definition = numbers().deliveryStrategy(DeliveryStrategy.VALIDATE_THEN_DELIVER)
                    .rejectionThreshold(RejectionThreshold.maxRejectedPercent(BigDecimal.TEN));

            FileResult passes = only(h.run(definition));

            assertThat(passes.status()).isEqualTo(FileStatus.SUCCESS);
            assertThat(passes.applied()).isEqualTo(90);
            h.records.clear();
            writeNumbers("alta_20261026120000.csv", 100, 11);
            FileResult fails = only(h.run(definition));
            assertThat(fails.status()).isEqualTo(FileStatus.FAILED);
            assertThat(fails.error().type()).isEqualTo(FileErrorType.THRESHOLD);
            assertThat(h.records).isEmpty();
        }

        @Test
        @DisplayName("AC-108 the percentage only counts engine rejections; consumer ones count for maxRejected")
        void percentageIgnoresConsumerRejections() {
            writeNumbers("alta_20261026120000.csv", 100, 0);
            h.behaviour = record -> record.lineNumber() <= 6 ? Outcome.rejected("dup") : Outcome.APPLIED;

            FileResult file = only(h.run(numbers().deliveryStrategy(DeliveryStrategy.VALIDATE_THEN_DELIVER)
                    .rejectionThreshold(new RejectionThreshold(5, BigDecimal.valueOf(50)))));

            assertThat(file.status()).isEqualTo(FileStatus.FAILED);
            assertThat(file.error().type()).isEqualTo(FileErrorType.THRESHOLD);
            assertThat(file.rejected()).isEqualTo(6);
            assertThat(h.records).hasSize(6);
        }

        @Test
        @DisplayName("AC-69 STREAMING: records before a structural error stay delivered")
        void streamingStructuralError() {
            StringBuilder content = new StringBuilder("value\n");
            for (int i = 1; i <= 4999; i++) {
                content.append(i).append('\n');
            }
            content.append("\"unclosed\n6\n");
            h.write("alta_20261026120000.csv", content.toString());

            FileResult file = only(h.run(numbers()));

            assertThat(file.status()).isEqualTo(FileStatus.FAILED);
            assertThat(file.error().type()).isEqualTo(FileErrorType.STRUCTURAL);
            assertThat(file.deliveredUpToLine()).isEqualTo(4999);
            assertThat(h.records).hasSize(4999);
        }

        @Test
        @DisplayName("AC-70 VALIDATE_THEN_DELIVER: a structural error delivers nothing")
        void validateThenDeliverStructuralError() {
            h.write("alta_20261026120000.csv", "value\n1\n2\n\"unclosed\n");

            FileResult file = only(h.run(numbers().deliveryStrategy(DeliveryStrategy.VALIDATE_THEN_DELIVER)));

            assertThat(file.status()).isEqualTo(FileStatus.FAILED);
            assertThat(file.deliveredUpToLine()).isZero();
            assertThat(h.records).isEmpty();
        }

        @Test
        @DisplayName("AC-189 cascaded children count for maxRejected")
        void cascadeCountsForThreshold() {
            h.write("orders_20261026.json", "[{\"id\":\"x\",\"children\":[{\"sku\":\"a\"},{\"sku\":\"b\"}]}]");

            FileResult file = only(h.run(nested(OnParentRejected.CASCADE).rejectionThreshold(RejectionThreshold.maxRejected(2))));

            assertThat(file.status()).isEqualTo(FileStatus.FAILED);
            assertThat(file.error().type()).isEqualTo(FileErrorType.THRESHOLD);
            assertThat(file.rejected()).isEqualTo(3);
        }

        @Test
        @DisplayName("AC-190 the percentage counts children in the total")
        void percentageCountsChildren() {
            StringBuilder content = new StringBuilder("[");
            for (int parent = 1; parent <= 10; parent++) {
                content.append(parent > 1 ? "," : "").append("{\"id\":\"").append(parent).append("\",\"children\":[");
                for (int child = 1; child <= 9; child++) {
                    content.append(child > 1 ? "," : "").append("{\"sku\":\"").append(child == 9 ? "" : "s").append("\"}");
                }
                content.append("]}");
            }
            h.write("orders_20261026.json", content.append("]").toString());

            FileResult file = only(h.run(nested(OnParentRejected.CASCADE)
                    .deliveryStrategy(DeliveryStrategy.VALIDATE_THEN_DELIVER)
                    .rejectionThreshold(RejectionThreshold.maxRejectedPercent(BigDecimal.TEN))));

            assertThat(file.status()).isEqualTo(FileStatus.SUCCESS);
            assertThat(file.linesRead()).isEqualTo(100);
            assertThat(file.rejected()).isEqualTo(10);
        }
    }

    @Nested
    class Consumer {

        @Test
        @DisplayName("AC-71 a consumer rejection is a rejected line that counts for the threshold")
        void consumerRejection() {
            h.writeLines("alta_20261026120000.csv", 3);
            h.behaviour = record -> record.lineNumber() == 2 ? Outcome.rejected("dup") : Outcome.APPLIED;

            FileResult file = only(h.run(h.altas()));

            assertThat(file.applied()).isEqualTo(2);
            assertThat(file.rejected()).isEqualTo(1);
            assertThat(h.rejections.getFirst().errors()).extracting(LineError::message).containsExactly("dup");
            assertThat(only(h.run(h.altas().rejectionThreshold(RejectionThreshold.maxRejected(0)))).status())
                    .isEqualTo(FileStatus.FAILED);
        }

        @Test
        @DisplayName("AC-72 a consumer exception with REJECT_LINE rejects the line and continues")
        void consumerThrowsRejectLine() {
            h.writeLines("alta_20261026120000.csv", 3);
            h.behaviour = record -> {
                if (record.lineNumber() == 1) {
                    throw new IllegalStateException("db down");
                }
                return Outcome.APPLIED;
            };

            FileResult file = only(h.run(h.altas()));

            assertThat(file.status()).isEqualTo(FileStatus.SUCCESS);
            assertThat(file.applied()).isEqualTo(2);
            assertThat(h.rejections.getFirst().errors().getFirst().message()).isEqualTo("db down");
        }

        @Test
        @DisplayName("AC-73 a consumer exception with ABORT_FILE fails the file and stops the run")
        void consumerThrowsAbortFile() {
            h.writeLines("alta_20261026120000.csv", 3);
            h.writeLines("alta_20261027120000.csv", 3);
            h.behaviour = record -> {
                throw new IllegalStateException("db down");
            };

            ImportResult result = h.run(h.altas().consumerErrorPolicy(ConsumerErrorPolicy.ABORT_FILE));

            assertThat(result.status()).isEqualTo(ResultStatus.FAILED);
            assertThat(result.files()).extracting(FileResult::status).containsExactly(FileStatus.FAILED, FileStatus.NOT_PROCESSED);
            assertThat(result.files().getFirst().error().type()).isEqualTo(FileErrorType.CONSUMER);
            assertThat(result.files().getFirst().error().message()).contains("db down");
        }

        @Test
        @DisplayName("AC-75 cancellation: the current line finishes, the next is not delivered")
        void cancellation() {
            h.writeLines("alta_20261026120000.csv", 20);
            CancellationToken token = new CancellationToken();
            h.context.cancellation(token);
            h.behaviour = record -> {
                if (record.lineNumber() == 10) {
                    token.cancel();
                }
                return Outcome.APPLIED;
            };

            ImportResult result = h.run(h.altas().duplicateProtection(
                    new org.tie.config.DuplicateProtection(true, org.tie.config.PostSuccessAction.DELETE)));

            assertThat(result.status()).isEqualTo(ResultStatus.CANCELLED);
            assertThat(only(result).status()).isEqualTo(FileStatus.CANCELLED);
            assertThat(h.records).hasSize(10);
            assertThat(h.filesIn(h.in)).containsExactly("alta_20261026120000.csv");
            assertThat(result.watermarks()).isEmpty();
        }

        @Test
        void cancellationBeforeAFileLeavesItNotProcessed() {
            h.writeLines("alta_20261026120000.csv", 1);
            h.writeLines("alta_20261027120000.csv", 1);
            CancellationToken token = new CancellationToken();
            h.context.cancellation(token);
            h.behaviour = record -> {
                token.cancel();
                return Outcome.APPLIED;
            };

            ImportResult result = h.run(h.altas());

            assertThat(result.status()).isEqualTo(ResultStatus.CANCELLED);
            assertThat(result.files()).extracting(FileResult::status).containsExactly(FileStatus.SUCCESS, FileStatus.NOT_PROCESSED);
        }

        @Test
        @DisplayName("AC-76 a file with 0 records succeeds, is moved and advances the watermark")
        void emptyFile() throws Exception {
            h.write("alta_20261026120000.csv", "value\n");
            java.nio.file.Files.createDirectory(h.in.resolve("done"));

            ImportResult result = h.run(h.altas().duplicateProtection(
                    new org.tie.config.DuplicateProtection(true, org.tie.config.PostSuccessAction.move("done"))));

            assertThat(only(result).status()).isEqualTo(FileStatus.SUCCESS);
            assertThat(h.filesIn(h.in.resolve("done"))).containsExactly("alta_20261026120000.csv");
            assertThat(result.watermarks()).containsEntry("alta", java.time.LocalDateTime.of(2026, 10, 26, 12, 0));
        }

        @Test
        @DisplayName("AC-77 the VALIDATE_THEN_DELIVER working copy is removed, on success and on failure")
        void workingCopyRemoved() {
            h.writeLines("alta_20261026120000.csv", 3);
            h.write("alta_20261027120000.csv", "value\n\"unclosed\n");

            ImportResult result = h.run(h.altas().deliveryStrategy(DeliveryStrategy.VALIDATE_THEN_DELIVER));

            assertThat(result.files()).extracting(FileResult::status).containsExactly(FileStatus.SUCCESS, FileStatus.FAILED);
            assertThat(h.filesIn(h.work)).isEmpty();
        }

        @Test
        @DisplayName("AC-174 with VALIDATE_THEN_DELIVER the transformer runs once per pass")
        void transformerCalledTwicePerLine() {
            AtomicInteger calls = new AtomicInteger();
            h.tie.transformer("count", (value, record, direction) -> {
                calls.incrementAndGet();
                return value;
            });
            h.writeLines("alta_20261026120000.csv", 3);

            h.run(h.importOf(csv("alta", "alta_{timestamp:yyyyMMddHHmmss}")
                    .fields(List.of(FieldDefinition.builder("value").transformer("count").build())))
                    .deliveryStrategy(DeliveryStrategy.VALIDATE_THEN_DELIVER));

            assertThat(calls).hasValue(6);
        }
    }

    // ---------------------------------------------------------------- nested records

    private IntegrationDefinition.Builder nested(OnParentRejected onParentRejected) {
        return h.importOf(
                Layout.builder("order").fileNamePattern("orders_{timestamp:yyyyMMdd}").allowedFormats(FileFormat.JSON)
                        .field(FieldDefinition.builder("id").type(FieldType.INTEGER)),
                Layout.builder("line").parent("order", "children").onParentRejected(onParentRejected)
                        .field(FieldDefinition.builder("sku").onEmpty(OnEmpty.ERROR)));
    }

    @Nested
    class NestedRecords {

        @Test
        @DisplayName("AC-78 children are numbered and delivered right after their parent")
        void childrenAfterParent() {
            h.write("orders_20261026.json", "[{\"id\":1,\"children\":[{\"sku\":\"a\"},{\"sku\":\"b\"}]},{\"id\":2}]");

            h.run(nested(OnParentRejected.CASCADE));

            assertThat(h.records).extracting(TypedRecord::layout, TypedRecord::lineNumber, TypedRecord::parentLineNumber)
                    .containsExactly(
                            org.assertj.core.groups.Tuple.tuple("order", 1, null),
                            org.assertj.core.groups.Tuple.tuple("line", 2, 1),
                            org.assertj.core.groups.Tuple.tuple("line", 3, 1),
                            org.assertj.core.groups.Tuple.tuple("order", 4, null));
            assertThat(h.records.getFirst().childrenDeclared()).isTrue();
            assertThat(h.records.getFirst().childrenDeclared("line")).isTrue();
            assertThat(h.records.get(3).childrenDeclared()).isFalse();
            assertThat(h.records.get(1).get("sku")).isEqualTo("a");
        }

        @Test
        @DisplayName("AC-81 AC-126 a rejected parent cascades PARENT_REJECTED to its children")
        void cascade() {
            h.write("orders_20261026.json", "[{\"id\":\"x\",\"children\":[{\"sku\":\"a\"},{\"sku\":\"b\"}]}]");

            FileResult file = only(h.run(nested(OnParentRejected.CASCADE)));

            assertThat(h.records).isEmpty();
            assertThat(file.rejected()).isEqualTo(3);
            assertThat(h.rejections.subList(1, 3)).allSatisfy(rejection -> {
                assertThat(rejection.parentLineNumber()).isEqualTo(1);
                assertThat(rejection.errors()).singleElement().satisfies(error -> {
                    assertThat(error.code()).isEqualTo(ErrorCode.PARENT_REJECTED);
                    assertThat(error.field()).isNull();
                    assertThat(error.message()).isEqualTo("parent rejected");
                    assertThat(error.args()).containsExactly(entry("parentLineNumber", "1"));
                });
            });
        }

        @Test
        @DisplayName("AC-82 with PROCESS the children of a rejected parent are delivered")
        void process() {
            h.write("orders_20261026.json", "[{\"id\":\"x\",\"children\":[{\"sku\":\"a\"},{\"sku\":\"b\"}]}]");

            h.run(nested(OnParentRejected.PROCESS));

            assertThat(h.records).extracting(TypedRecord::lineNumber, TypedRecord::parentLineNumber)
                    .containsExactly(org.assertj.core.groups.Tuple.tuple(2, 1), org.assertj.core.groups.Tuple.tuple(3, 1));
        }

        @Test
        @DisplayName("AC-130 a rejected child carries its parent line and its own raw values")
        void rejectedChild() {
            h.write("orders_20261026.json", "[{\"id\":1,\"children\":[{\"sku\":\"\",\"qty\":\"2\"}]}]");

            h.run(nested(OnParentRejected.CASCADE));

            assertThat(h.rejections).singleElement().satisfies(rejection -> {
                assertThat(rejection.layout()).isEqualTo("line");
                assertThat(rejection.lineNumber()).isEqualTo(2);
                assertThat(rejection.parentLineNumber()).isEqualTo(1);
                assertThat(rejection.rawValues()).containsExactly(entry("sku", ""), entry("qty", "2"));
            });
        }
    }

    @Nested
    class Rejections {

        @Test
        @DisplayName("AC-127 consumer codes: CONSUMER_REJECTED {reason} and CONSUMER_FAILED {exceptionMessage}")
        void consumerCodes() {
            h.writeLines("alta_20261026120000.csv", 2);
            h.behaviour = record -> {
                if (record.lineNumber() == 2) {
                    throw new IllegalStateException("boom");
                }
                return Outcome.rejected("dup");
            };

            h.run(h.altas());

            assertThat(h.rejections).extracting(r -> r.errors().getFirst()).satisfiesExactly(
                    first -> {
                        assertThat(first.code()).isEqualTo(ErrorCode.CONSUMER_REJECTED);
                        assertThat(first.args()).containsExactly(entry("reason", "dup"));
                    },
                    second -> {
                        assertThat(second.code()).isEqualTo(ErrorCode.CONSUMER_FAILED);
                        assertThat(second.args()).containsExactly(entry("exceptionMessage", "boom"));
                    });
        }

        @Test
        @DisplayName("AC-129 a rejection carries the untrimmed raw values, the file name and the line")
        void rawValues() {
            h.write("alta_20261026120000.csv", "nif;sexo\n123; X \n");

            h.run(h.importOf(org.tie.config.Layout.builder("alta").fileNamePattern("alta_{timestamp:yyyyMMddHHmmss}")
                    .allowedFormats(FileFormat.CSV).csv(org.tie.config.CsvOptions.builder().separator(';').build())
                    .field(FieldDefinition.builder("nif"))
                    .field(FieldDefinition.builder("sexo").translation("M", "Male"))));

            assertThat(h.rejections).singleElement().satisfies(rejection -> {
                assertThat(rejection.rawValues()).containsExactly(entry("nif", "123"), entry("sexo", " X "));
                assertThat(rejection.sourceFileName()).isEqualTo("alta_20261026120000.csv");
                assertThat(rejection.lineNumber()).isEqualTo(1);
                assertThat(rejection.rowNumber()).isEqualTo(2);
                assertThat(rejection.errors().getFirst().code()).isEqualTo(ErrorCode.NO_TRANSLATION);
            });
        }

        @Test
        @DisplayName("AC-131 a field with inInput = false has no raw value")
        void noRawValueForFieldsNotInInput() {
            h.write("alta_20261026120000.csv", "value\nv\n");

            h.run(h.importOf(csv("alta", "alta_{timestamp:yyyyMMddHHmmss}")
                    .field(FieldDefinition.builder("code").inInput(false).onEmpty(OnEmpty.ERROR))));

            assertThat(h.rejections).singleElement()
                    .satisfies(rejection -> assertThat(rejection.rawValues()).containsOnlyKeys("value"));
        }

        @Test
        @DisplayName("AC-128 every code of §5.4 comes out with exactly its argument names")
        void everyCode() {
            h.tie.transformer("boom", (value, record, direction) -> {
                throw new IllegalStateException("t");
            }).defaultValueResolver((template, record) -> {
                throw new IllegalStateException("r");
            });
            h.write("orders_20261026.json", """
                    [{"id": 1, "a": "X", "c": "nope", "d1": "zz", "d2": "zz", "f": "v", "children": [{"sku": "s"}]},
                     {"id": 2, "a": "M", "b": "v", "e": "v", "children": []},
                     {"id": 3, "a": "M", "b": "v", "e": "v", "children": []},
                     {"id": 4, "a": "M", "b": "v", "e": "v", "children": []},
                     {"id": 5, "a": "M", "b": "v", "e": "v", "children": []}]
                    """);
            h.behaviour = record -> switch (record.lineNumber()) {
                case 4 -> Outcome.rejected("dup");
                case 5 -> throw new IllegalStateException("c");
                default -> Outcome.APPLIED;
            };
            Layout.Builder order = Layout.builder("order").fileNamePattern("orders_{timestamp:yyyyMMdd}")
                    .allowedFormats(FileFormat.JSON)
                    .field(FieldDefinition.builder("id"))
                    .field(FieldDefinition.builder("a").translation("M", "Male"))
                    .field(FieldDefinition.builder("b").onEmpty(OnEmpty.ERROR))
                    .field(FieldDefinition.builder("c").type(FieldType.DATE).typeFormat(TypeFormat.date("dd/MM/yyyy")))
                    .field(FieldDefinition.builder("d1").validations(Validations.minLength(9)))
                    .field(FieldDefinition.builder("d2").validations(new Validations(null, 1, "x", List.of("y"))))
                    .field(FieldDefinition.builder("e").defaultValue("${x}"))
                    .field(FieldDefinition.builder("f").transformer("boom"));

            h.run(h.importOf(order, Layout.builder("line").parent("order", "children").field(FieldDefinition.builder("sku"))));
            h.tie = org.tie.Tie.builder();
            h.writeLines("alta_20261026120000.csv", 1);
            h.run(h.importOf(csv("alta", "alta_{timestamp:yyyyMMddHHmmss}")
                    .field(FieldDefinition.builder("code").inInput(false).defaultValue("${x}"))));

            Map<ErrorCode, Set<String>> expected = new HashMap<>();
            expected.put(ErrorCode.NO_TRANSLATION, Set.of("value"));
            expected.put(ErrorCode.REQUIRED_EMPTY, Set.of());
            expected.put(ErrorCode.INVALID_TYPE, Set.of("value", "type", "format"));
            expected.put(ErrorCode.TOO_LONG, Set.of("value", "maxLength"));
            expected.put(ErrorCode.TOO_SHORT, Set.of("value", "minLength"));
            expected.put(ErrorCode.PATTERN_MISMATCH, Set.of("value", "regex"));
            expected.put(ErrorCode.NOT_ALLOWED, Set.of("value", "allowedValues"));
            expected.put(ErrorCode.DEFAULT_UNRESOLVABLE, Set.of("template"));
            expected.put(ErrorCode.DEFAULT_RESOLVER_FAILED, Set.of("template", "exceptionMessage"));
            expected.put(ErrorCode.TRANSFORMER_FAILED, Set.of("transformer", "exceptionMessage"));
            expected.put(ErrorCode.PARENT_REJECTED, Set.of("parentLineNumber"));
            expected.put(ErrorCode.CONSUMER_REJECTED, Set.of("reason"));
            expected.put(ErrorCode.CONSUMER_FAILED, Set.of("exceptionMessage"));
            Map<ErrorCode, Set<String>> seen = new HashMap<>();
            for (LineRejection rejection : h.rejections) {
                for (LineError error : rejection.errors()) {
                    seen.put(error.code(), error.args().keySet());
                    assertThat(error.field() != null).as(error.code() + " has a field").isEqualTo(error.code().isFieldError());
                }
            }

            assertThat(seen.keySet()).containsExactlyInAnyOrderElementsOf(EnumSet.allOf(ErrorCode.class));
            assertThat(seen).isEqualTo(expected);
        }
    }
}
