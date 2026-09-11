package net.ximatai.muyun.spring.platform.ui;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

@Service
public class PlatformUiControlRulesService extends AbstractAbilityService<PlatformUiControlRules> {
    private final ObjectMapper mapper = new ObjectMapper();
    public PlatformUiControlRulesService(BaseDao<PlatformUiControlRules, String> dao) {
        super("platform.ui_control_rules", PlatformUiControlRules.class, dao);
    }
    public record Snapshot(String baselineFingerprint, List<UiControlRule> rules) {}
    private PlatformUiControlRules stored(String moduleAlias) {
        return findOne(Criteria.of().eq("moduleAlias", PlatformNameRules.requireModuleAlias(moduleAlias)));
    }
    public Snapshot snapshot(String moduleAlias) {
        // Module configuration is system-owned; business records retain their request tenant.
        try (var ignored = net.ximatai.muyun.spring.common.tenant.TenantContext.system("module UI control metadata")) {
            return snapshot(stored(moduleAlias));
        }
    }
    private Snapshot snapshot(PlatformUiControlRules row) {
        try {
            String json = row == null ? "[]" : row.getRulesJson();
            String fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.getBytes(StandardCharsets.UTF_8)));
            return new Snapshot(fingerprint, mapper.readValue(json, new TypeReference<List<UiControlRule>>() {}));
        } catch (Exception exception) { throw new IllegalStateException("Cannot read UI control rules", exception); }
    }
    /** The row's optimistic version and unique module key protect concurrent replacements. */
    @Transactional
    public void replace(String moduleAlias, String baseline, List<UiControlRule> rules) {
        PlatformUiControlRules row = stored(moduleAlias);
        if (!Objects.equals(baseline, snapshot(row).baselineFingerprint()))
            throw new PlatformException("界面规则已变更，请重新加载后再应用");
        try {
            String json = mapper.writeValueAsString(rules);
            if (row == null) {
                row = new PlatformUiControlRules();
                row.setModuleAlias(moduleAlias);
                row.setTitle("界面控制");
                row.setRulesJson(json);
                insert(row);
            } else {
                row.setRulesJson(json);
                update(row);
            }
        } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
            throw new IllegalArgumentException("Cannot encode UI control rules", exception);
        }
    }
}
