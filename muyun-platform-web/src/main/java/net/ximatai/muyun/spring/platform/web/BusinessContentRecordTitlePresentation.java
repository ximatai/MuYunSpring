package net.ximatai.muyun.spring.platform.web;

import net.ximatai.muyun.spring.ability.CrudAbility;
import net.ximatai.muyun.spring.common.content.BusinessContentTextPresentation;
import net.ximatai.muyun.spring.common.model.contract.EntityContract;
import net.ximatai.muyun.spring.platform.module.ModuleRecordTitlePresentation;
import net.ximatai.muyun.spring.web.BusinessContentQueryWebContext;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reuses the ordinary authorized reading projection for workflow and other business summaries. */
@Component
public final class BusinessContentRecordTitlePresentation implements ModuleRecordTitlePresentation {
    private final BusinessContentReadingProjectionService readings;
    public BusinessContentRecordTitlePresentation(BusinessContentReadingProjectionService readings) { this.readings = java.util.Objects.requireNonNull(readings, "readings"); }

    @Override public BusinessContentTextPresentation project(CrudAbility<?> owner, EntityContract record,
            String field, String exposedText) {
        var languages = BusinessContentQueryWebContext.languages();
        if (languages.isEmpty() || record.getId() == null || !readings.fields(owner).contains(field)) return null;
        readings.capture(owner, List.of(record));
        return readings.presentations(owner, Map.of(record.getId(), Map.of(field, exposedText)), Set.of(field), languages)
                .getOrDefault(record.getId(), Map.of()).get(field);
    }
}
