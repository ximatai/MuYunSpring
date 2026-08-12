package net.ximatai.muyun.spring.dynamic.metadata;

import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.file.FileReference;
import net.ximatai.muyun.spring.common.model.file.FileReferenceMetadata;
import net.ximatai.muyun.spring.common.model.file.FileReferenceStoragePolicy;
import net.ximatai.muyun.spring.common.model.file.FileReferenceMetadataField;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileReferenceDefinitionTest {
    @Test
    void shouldCompileStaticInlineStoragePolicyIntoTheUnifiedFileReferenceDescriptor() {
        EntityDefinition entity = new StaticEntityDefinitionCompiler().compile("inline_asset", "test.inline", InlineAssetModel.class);

        assertThat(entity.fileReferences().get("assetId").storagePolicy())
                .isEqualTo(FileReferenceStoragePolicy.DATABASE_INLINE);
    }
    @Test
    void shouldCompileStaticFileReferenceConstraintsIntoTheEntityDefinition() {
        EntityDefinition entity = new StaticEntityDefinitionCompiler()
                .compile("document", "Document", StaticDocument.class);

        assertThat(entity.fileReferences()).containsEntry("sourceFileId",
                new FileReferenceDefinition(Set.of("application/pdf"), 50L * 1024 * 1024, 1,
                        Map.of(FileReferenceMetadata.ORIGINAL_FILENAME, "sourceFilename",
                                FileReferenceMetadata.SIZE_BYTES, "sourceFileSize")));
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
    void shouldCompileAndValidateMultiFileReferenceFields() {
        EntityDefinition staticEntity = new StaticEntityDefinitionCompiler()
                .compile("document", "Document", StaticMultiFileDocument.class);
        assertThat(staticEntity.fileReferences().get("sourceFileIds").maxFiles()).isEqualTo(3);
        new ModuleDefinitionValidator().validateEntity(staticEntity);

        EntityDefinition dynamicEntity = new EntityDefinition("document", "crm_document", "Document", List.of(
                FieldDefinition.of("sourceFileIds", FieldType.JSON, "Source Files")
                        .column("source_file_ids").jsonSet()))
                .withFileReferences(Map.of("sourceFileIds",
                        new FileReferenceDefinition(Set.of(), null, 3)));
        new ModuleDefinitionValidator().validateEntity(dynamicEntity);
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
        assertThatThrownBy(() -> new FileReferenceDefinition(Set.of(), null, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("file reference maxFiles must be positive");
    }

    @Test
    void shouldRejectMetadataBindingsWithAnIncompatibleOrAmbiguousTarget() {
        EntityDefinition invalidType = new EntityDefinition("document", "crm_document", "Document", List.of(
                FieldDefinition.string("sourceFileId", "Source File").column("source_file_id"),
                FieldDefinition.string("sourceFileSize", "Source File Size").column("source_file_size")))
                .withFileReferences(Map.of("sourceFileId", new FileReferenceDefinition(Set.of(), null, 1,
                        Map.of(FileReferenceMetadata.SIZE_BYTES, "sourceFileSize"))));

        assertThatThrownBy(() -> new ModuleDefinitionValidator().validateEntity(invalidType))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessage("file reference metadata requires physical LONG field: document.sourceFileSize");

        EntityDefinition multi = new EntityDefinition("document", "crm_document", "Document", List.of(
                FieldDefinition.of("sourceFileIds", FieldType.JSON, "Source Files").column("source_file_ids").jsonSet(),
                FieldDefinition.string("sourceFilename", "Source Filename").column("source_filename")))
                .withFileReferences(Map.of("sourceFileIds", new FileReferenceDefinition(Set.of(), null, 2,
                        Map.of(FileReferenceMetadata.ORIGINAL_FILENAME, "sourceFilename"))));

        assertThatThrownBy(() -> new ModuleDefinitionValidator().validateEntity(multi))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessage("file reference metadata fields require a single-file reference: document.sourceFileIds");

        EntityDefinition anotherFileId = new EntityDefinition("document", "crm_document", "Document", List.of(
                FieldDefinition.string("sourceFileId", "Source File").column("source_file_id"),
                FieldDefinition.string("coverFileId", "Cover File").column("cover_file_id")))
                .withFileReferences(Map.of(
                        "sourceFileId", new FileReferenceDefinition(Set.of(), null, 1,
                                Map.of(FileReferenceMetadata.ORIGINAL_FILENAME, "coverFileId")),
                        "coverFileId", FileReferenceDefinition.unrestricted()));

        assertThatThrownBy(() -> new ModuleDefinitionValidator().validateEntity(anotherFileId))
                .isInstanceOf(ModuleDefinitionException.class)
                .hasMessage("file reference metadata field must not be a fileId field: document.coverFileId");
    }

    @Test
    void shouldRejectStaticMetadataBindingToAnotherFileReference() {
        assertThatThrownBy(() -> new StaticEntityDefinitionCompiler()
                .compile("document", "Document", StaticConflictingMetadataDocument.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("file reference metadata field must not be a fileId field: "
                        + StaticConflictingMetadataDocument.class.getName() + ".coverFileId");
    }

    @Test
    void shouldRejectStaticMetadataBindingWithoutAFileReferenceSource() {
        assertThatThrownBy(() -> new StaticEntityDefinitionCompiler()
                .compile("document", "Document", StaticUnknownMetadataSourceDocument.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("file reference metadata source must declare @FileReference: "
                        + StaticUnknownMetadataSourceDocument.class.getName() + ".sourceFilename");

        assertThatThrownBy(() -> new StaticEntityDefinitionCompiler()
                .compile("document", "Document", StaticBlankMetadataSourceDocument.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("file reference metadata source must declare @FileReference: "
                        + StaticBlankMetadataSourceDocument.class.getName() + ".sourceFilename");
    }

    @Test
    void shouldRejectStaticMetadataBindingForMultiFileSourceOrWrongTargetType() {
        assertThatThrownBy(() -> new StaticEntityDefinitionCompiler()
                .compile("document", "Document", StaticMultiFileMetadataDocument.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("file reference metadata fields require a single-file reference: "
                        + StaticMultiFileMetadataDocument.class.getName() + ".sourceFileIds");

        assertThatThrownBy(() -> new StaticEntityDefinitionCompiler()
                .compile("document", "Document", StaticInvalidMetadataTypeDocument.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("file reference metadata requires @Column BIGINT Long field: "
                        + StaticInvalidMetadataTypeDocument.class.getName() + ".sourceFileSize");
    }

    @Table(name = "test_static_document")
    static class InlineAssetModel extends StandardEntity {
        @Column(name = "asset_id", type = ColumnType.VARCHAR, length = 32)
        @FileReference(storagePolicy = FileReferenceStoragePolicy.DATABASE_INLINE)
        private String assetId;
    }

    @Table(name = "test_static_document")
    static class StaticDocument extends StandardEntity {
        @Column(name = "source_file_id", type = ColumnType.VARCHAR, length = 64)
        @FileReference(allowedMediaTypes = "application/pdf", maxFileSizeBytes = 50L * 1024 * 1024)
        private String sourceFileId;
        @Column(name = "source_filename", type = ColumnType.VARCHAR, length = 255)
        @FileReferenceMetadataField(source = "sourceFileId", value = FileReferenceMetadata.ORIGINAL_FILENAME)
        private String sourceFilename;
        @Column(name = "source_file_size", type = ColumnType.BIGINT)
        @FileReferenceMetadataField(source = "sourceFileId", value = FileReferenceMetadata.SIZE_BYTES)
        private Long sourceFileSize;
    }

    @Table(name = "test_static_multi_file_document")
    static class StaticMultiFileDocument extends StandardEntity {
        @Column(name = "source_file_ids", type = ColumnType.JSON_SET)
        @FileReference(maxFiles = 3)
        private Set<String> sourceFileIds;
    }

    @Table(name = "test_static_conflicting_metadata_document")
    static class StaticConflictingMetadataDocument extends StandardEntity {
        @Column(name = "source_file_id", type = ColumnType.VARCHAR, length = 64)
        @FileReference
        private String sourceFileId;
        @Column(name = "cover_file_id", type = ColumnType.VARCHAR, length = 64)
        @FileReference
        @FileReferenceMetadataField(source = "sourceFileId", value = FileReferenceMetadata.ORIGINAL_FILENAME)
        private String coverFileId;
    }

    @Table(name = "test_static_unknown_metadata_source_document")
    static class StaticUnknownMetadataSourceDocument extends StandardEntity {
        @Column(name = "source_filename", type = ColumnType.VARCHAR, length = 255)
        @FileReferenceMetadataField(source = "missingFileId", value = FileReferenceMetadata.ORIGINAL_FILENAME)
        private String sourceFilename;
    }

    @Table(name = "test_static_blank_metadata_source_document")
    static class StaticBlankMetadataSourceDocument extends StandardEntity {
        @Column(name = "source_filename", type = ColumnType.VARCHAR, length = 255)
        @FileReferenceMetadataField(source = " ", value = FileReferenceMetadata.ORIGINAL_FILENAME)
        private String sourceFilename;
    }

    @Table(name = "test_static_multi_file_metadata_document")
    static class StaticMultiFileMetadataDocument extends StandardEntity {
        @Column(name = "source_file_ids", type = ColumnType.JSON_SET)
        @FileReference(maxFiles = 2)
        private Set<String> sourceFileIds;
        @Column(name = "source_filename", type = ColumnType.VARCHAR, length = 255)
        @FileReferenceMetadataField(source = "sourceFileIds", value = FileReferenceMetadata.ORIGINAL_FILENAME)
        private String sourceFilename;
    }

    @Table(name = "test_static_invalid_metadata_type_document")
    static class StaticInvalidMetadataTypeDocument extends StandardEntity {
        @Column(name = "source_file_id", type = ColumnType.VARCHAR, length = 64)
        @FileReference
        private String sourceFileId;
        @Column(name = "source_file_size", type = ColumnType.VARCHAR, length = 64)
        @FileReferenceMetadataField(source = "sourceFileId", value = FileReferenceMetadata.SIZE_BYTES)
        private String sourceFileSize;
    }
}
