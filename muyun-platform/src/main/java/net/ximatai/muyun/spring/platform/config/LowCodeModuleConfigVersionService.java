package net.ximatai.muyun.spring.platform.config;

import java.util.Map;
import net.ximatai.muyun.spring.common.i18n.TextReference;
import net.ximatai.muyun.spring.common.exception.PlatformErrors;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.database.core.orm.PageRequest;
import net.ximatai.muyun.database.core.orm.Sort;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;

@Service
public class LowCodeModuleConfigVersionService extends AbstractAbilityService<LowCodeModuleConfigVersion> implements
        SoftDeleteAbility<LowCodeModuleConfigVersion> {
    public static final String MODULE_ALIAS = "platform.low_code_config_version";
    private static final PageRequest ALL = new PageRequest(0, Integer.MAX_VALUE);

    public LowCodeModuleConfigVersionService(BaseDao<LowCodeModuleConfigVersion, String> versionDao) {
        super(MODULE_ALIAS, LowCodeModuleConfigVersion.class, versionDao);
    }

    @Override
    public void beforeInsert(LowCodeModuleConfigVersion version) {
        normalizeAndValidate(version);
        if (Boolean.TRUE.equals(version.getCurrentVersion())) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.low-code-module-config-version.low-code-config-current-version-can-only-be-switched-by-archive-facade",
                    "low code config current version can only be switched by archive facade"));
        }
    }

    @Override
    public void beforeUpdate(LowCodeModuleConfigVersion version) {
        LowCodeModuleConfigVersion existing = selectIncludingDeleted(version.getId());
        rejectSnapshotMutation(existing, version);
        normalizeAndValidate(version);
    }

    public List<LowCodeModuleConfigVersion> listByModule(String moduleAlias) {
        String validModuleAlias = PlatformNameRules.requireModuleAlias(moduleAlias);
        return list(Criteria.of().eq("moduleAlias", validModuleAlias), ALL, Sort.desc("versionNo"));
    }

    public LowCodeModuleConfigVersion currentVersion(String moduleAlias) {
        String validModuleAlias = PlatformNameRules.requireModuleAlias(moduleAlias);
        return findOne(Criteria.of()
                .eq("moduleAlias", validModuleAlias)
                .eq("currentVersion", Boolean.TRUE));
    }

    public int nextVersionNo(String moduleAlias) {
        return listByModule(moduleAlias).stream()
                .map(LowCodeModuleConfigVersion::getVersionNo)
                .filter(value -> value != null && value > 0)
                .max(Comparator.naturalOrder())
                .orElse(0) + 1;
    }

    public void markOnlyCurrent(String moduleAlias, String versionId) {
        String validModuleAlias = PlatformNameRules.requireModuleAlias(moduleAlias);
        LowCodeModuleConfigVersion target = select(versionId);
        if (target == null || !validModuleAlias.equals(target.getModuleAlias())) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.low-code-module-config-version.low-code-config-version-not-found-in-module",
                    "low code config version not found in module: {value1}"), Map.of("value1", String.valueOf(versionId)));
        }
        if (target.getVersionStatus() != LowCodeConfigVersionStatus.ARCHIVED) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.low-code-module-config-version.only-archived-low-code-config-version-can-be-current",
                    "only archived low code config version can be current: {value1}"), Map.of("value1", String.valueOf(versionId)));
        }
        for (LowCodeModuleConfigVersion version : listByModule(validModuleAlias)) {
            boolean current = version.getId().equals(versionId);
            if (!Boolean.valueOf(current).equals(version.getCurrentVersion())) {
                version.setCurrentVersion(current);
                update(version);
            }
        }
    }

    private void normalizeAndValidate(LowCodeModuleConfigVersion version) {
        version.setModuleAlias(PlatformNameRules.requireModuleAlias(version.getModuleAlias()));
        if (version.getVersionNo() == null || version.getVersionNo() <= 0) {
            throw PlatformErrors.badRequestText(TextReference.code(
                    "platform.rejection.low-code-module-config-version.low-code-config-version-number-must-be-positive",
                    "low code config version number must be positive"));
        }
        if (version.getVersionStatus() == null) {
            version.setVersionStatus(LowCodeConfigVersionStatus.ARCHIVED);
        }
        if (version.getCurrentVersion() == null) {
            version.setCurrentVersion(Boolean.FALSE);
        }
        rejectDuplicate(version, Criteria.of()
                        .eq("moduleAlias", version.getModuleAlias())
                        .eq("versionNo", version.getVersionNo()), () -> PlatformErrors.badRequestText(TextReference.code(
                                "platform.rejection.low-code-module-config-version.low-code-config-version-number-must-be-unique-within-module",
                                "low code config version number must be unique within module: {value1}"), Map.of("value1", String.valueOf(version.getVersionNo()))));
    }


    private void rejectSnapshotMutation(LowCodeModuleConfigVersion existing, LowCodeModuleConfigVersion incoming) {
        if (existing == null) {
            return;
        }
        rejectChanged("moduleAlias", existing.getModuleAlias(), incoming.getModuleAlias());
        rejectChanged("versionNo", existing.getVersionNo(), incoming.getVersionNo());
        rejectChanged("versionStatus", existing.getVersionStatus(), incoming.getVersionStatus());
        rejectChanged("packageSnapshotText", existing.getPackageSnapshotText(), incoming.getPackageSnapshotText());
        rejectChanged("packageHash", existing.getPackageHash(), incoming.getPackageHash());
        rejectChanged("summaryJson", existing.getSummaryJson(), incoming.getSummaryJson());
        rejectChanged("sourceVersionId", existing.getSourceVersionId(), incoming.getSourceVersionId());
        rejectChanged("archivedBy", existing.getArchivedBy(), incoming.getArchivedBy());
        rejectChanged("archivedAt", existing.getArchivedAt(), incoming.getArchivedAt());
        rejectChanged("remark", existing.getRemark(), incoming.getRemark());
    }
}
