package net.ximatai.muyun.spring.dynamic.metadata;

import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.file.FileReference;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileReferenceDefinitionTest {
    @Test
    void shouldCompileStaticFileReferenceConstraintsIntoTheEntityDefinition() {
        EntityDefinition entity = new StaticEntityDefinitionCompiler()
                .compile("document", "Document", StaticDocument.class);

        assertThat(entity.fileReferences()).containsEntry("sourceFileId",
                new FileReferenceDefinition(Set.of("application/pdf"), 50L * 1024 * 1024));
        new ModuleDefinitionValidator().validateEntity(entity);
    }

    @Test
    void shouldAcceptADeclaredDynamicSingleFileReference() {
        EntityDefinition entity = new EntityDefinition("document", "crm_document", "Document", List.of(
                FieldDefinition.string("sourceFileId", "Source File").column("source_file_id").length(64)))
                .withFileReferences(Map.of("sourceFileId",
                        new FileReferenceDefinition(Set.of("image/jpeg", "application/pdf"), 1024L)));

        new ModuleDefinitionValidator().validateEntity(entity);

        assertThat(entity.fileReferences().get("sourceFileId").allowedMediaTypes())
                .containsExactlyInAnyOrder("image/jpeg", "application/pdf");
    }

    @Test
    void shouldRejectAFileReferenceThatIsNotAStringColumn() {
        EntityDefinition entity = new EntityDefinition("document", "crm_document", "Document", List.of(
                FieldDefinition.of("sourceFileId", FieldType.JSON, "Source File").column("source_file_id")))
                .withFileReferences(Map.of("sourceFileId", FileReferenceDefinition.unrestricted()));

        assertThatThrownBy(() -> new ModuleDefinitionValidator().validateEntity(entity))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessage("file reference requires physical STRING field: document.sourceFileId");
    }

    @Test
    void shouldRejectInvalidFileReferenceConstraints() {
        assertThatThrownBy(() -> new FileReferenceDefinition(Set.of("application/pdf"), 0L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("file reference maxFileSizeBytes must be positive");
        assertThatThrownBy(() -> new FileReferenceDefinition(Set.of("pdf"), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid file reference media type: pdf");
    }

    @Table(name = "test_static_document")
    static class StaticDocument extends StandardEntity {
        @Column(name = "source_file_id", type = ColumnType.VARCHAR, length = 64)
        @FileReference(allowedMediaTypes = "application/pdf", maxFileSizeBytes = 50L * 1024 * 1024)
        private String sourceFileId;
    }
}
