package org.tie;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.tie.fixtures.Definitions.csvLayout;
import static org.tie.fixtures.Definitions.validImport;
import static org.tie.fixtures.Definitions.validSftp;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.tie.config.FieldDefinition;
import org.tie.config.IntegrationDefinition;

class TieValidateTest {

    private static IntegrationDefinition withField(FieldDefinition.Builder field) {
        return validImport().layouts(List.of(csvLayout("alta").fields(List.of(field.build())).build())).build();
    }

    @Test
    @DisplayName("AC-03 a transformer that is not registered is a problem")
    void unregisteredTransformer() {
        IntegrationDefinition definition = withField(FieldDefinition.builder("nif").transformer("x"));

        assertThat(Tie.builder().build().validate(definition))
                .containsExactly("layout 'alta', field 'nif': transformer 'x' is not registered");
        assertThat(Tie.builder().transformer("x", (value, record, direction) -> value).build().validate(definition))
                .isEmpty();
    }

    @Test
    @DisplayName("AC-04 a defaultValue with ${…} and no resolver is valid (it fails per line)")
    void templateDefaultWithoutResolver() {
        IntegrationDefinition definition = withField(FieldDefinition.builder("code").defaultValue("${name}-x"));

        assertThat(Tie.builder().build().validate(definition)).isEmpty();
    }

    @Test
    @DisplayName("AC-149 variables are not checked by validate: it has no execution context")
    void patternVariablesAreValid() {
        IntegrationDefinition definition = validImport()
                .layouts(List.of(csvLayout("alta").fileNamePattern("alta_{tenant}_{timestamp:yyyyMMdd}").build()))
                .build();

        assertThat(Tie.builder().build().validate(definition)).isEmpty();
    }

    @Test
    void secretReferencesNeedASecretProvider() {
        IntegrationDefinition definition = validImport()
                .inputChannels(List.of(validSftp().password("${secret:sftp}").build())).build();

        assertThat(Tie.builder().build().validate(definition)).containsExactly(
                "inputChannels[0] (SFTP): password uses ${secret:sftp} but no SecretProvider is registered");
        assertThat(Tie.builder().secretProvider(name -> "x".toCharArray()).build().validate(definition)).isEmpty();
    }

    @Test
    void definitionAndSpiProblemsAreReportedTogether() {
        IntegrationDefinition definition = validImport().id(null)
                .layouts(List.of(csvLayout("alta").fields(List.of(
                        FieldDefinition.builder("nif").transformer("x").build())).build()))
                .build();

        assertThat(Tie.builder().build().validate(definition)).containsExactly(
                "id is required",
                "layout 'alta', field 'nif': transformer 'x' is not registered");
        assertThat(Tie.builder().build().validate(null)).containsExactly("definition is required");
    }

    @Test
    void transformerRegistration() {
        Tie.Builder builder = Tie.builder().transformer("x", (value, record, direction) -> value);

        assertThatThrownBy(() -> builder.transformer("x", (value, record, direction) -> value))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("transformer 'x' is already registered");
        assertThatThrownBy(() -> builder.transformer(" ", (value, record, direction) -> value))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void configurationExceptionListsProblems() {
        ConfigurationException single = new ConfigurationException("id is required");
        ConfigurationException many = new ConfigurationException(List.of("a", "b"));

        assertThat(single.getProblems()).containsExactly("id is required");
        assertThat(single).hasMessage("Invalid configuration: id is required");
        assertThat(many).hasMessage("Invalid configuration (2 problems):\n - a\n - b");
    }
}
