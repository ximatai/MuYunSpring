package net.ximatai.muyun.spring.platform.dictionary;

import net.ximatai.muyun.database.core.orm.Criteria;
import net.ximatai.muyun.spring.ability.AbstractAbilityService;
import net.ximatai.muyun.spring.ability.BaseDao;
import net.ximatai.muyun.spring.ability.EnableAbility;
import net.ximatai.muyun.spring.ability.SoftDeleteAbility;
import net.ximatai.muyun.spring.ability.TreeAbility;
import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.util.PlatformNameRules;
import net.ximatai.muyun.spring.platform.application.ApplicationReferenceContributor;
import org.springframework.stereotype.Service;

import java.util.List;
import net.ximatai.muyun.spring.ability.query.QueryAbility;
import net.ximatai.muyun.spring.ability.query.QueryDescriptor;
import net.ximatai.muyun.spring.ability.query.QueryDescriptors;
@Service
public class DictionaryCategoryService extends AbstractAbilityService<DictionaryCategory> implements
        SoftDeleteAbility<DictionaryCategory>,
        EnableAbility<DictionaryCategory>,
        TreeAbility<DictionaryCategory>,
        QueryAbility<DictionaryCategory>,
        ApplicationReferenceContributor {
    public static final String MODULE_ALIAS = "platform.dictionary_category";

    public DictionaryCategoryService(BaseDao<DictionaryCategory, String> categoryDao) {
        super(MODULE_ALIAS, DictionaryCategory.class, categoryDao);
    }

    @Override
    public QueryDescriptor queryDescriptor() {
        return QueryDescriptors.fromModel(MODULE_ALIAS, DictionaryCategory.class, java.util.List.of("id", "applicationAlias", "alias", "categoryKind", "parentId", "title", "enabled", "sortOrder", "createdAt", "updatedAt"),
                net.ximatai.muyun.database.core.orm.Sort.asc("sortOrder"),
                net.ximatai.muyun.database.core.orm.Sort.asc("title"));
    }

    @Override
    public String resourceKey() {
        return "dictionaryCategory";
    }

    @Override
    public String resourceName() {
        return "字典类目";
    }

    @Override
    public boolean hasReferenceTo(String applicationAlias) {
        return findOne(Criteria.of().eq("applicationAlias", applicationAlias)) != null;
    }

    @Override
    public void beforeInsert(DictionaryCategory category) {
        normalizeAndValidate(category);
    }

    @Override
    public void beforeUpdate(DictionaryCategory category) {
        normalizeAndValidate(category);
        validateImmutableIdentity(category);
    }

    @Override
    public List<DictionaryCategory> children(String parentId) {
        if (TreeAbility.ROOT_ID.equals(parentId)) {
            rejectRootChildrenLookup("rootCategories(applicationAlias)");
        }
        return TreeAbility.super.children(parentId);
    }

    public List<DictionaryCategory> rootCategories(String applicationAlias) {
        return children(applicationAlias, TreeAbility.ROOT_ID);
    }

    public List<DictionaryCategory> rootCategories() {
        return TreeAbility.super.children(TreeAbility.ROOT_ID);
    }

    public List<DictionaryCategory> children(String applicationAlias, String parentId) {
        return TreeAbility.super.children(applicationScope(PlatformNameRules.requireApplicationAlias(applicationAlias)), parentId);
    }

    public DictionaryCategory requireDictionaryCategory(String applicationAlias, String categoryAlias) {
        String validApplicationAlias = PlatformNameRules.requireApplicationAlias(applicationAlias);
        String validCategoryAlias = requireAlias(categoryAlias);
        DictionaryCategory category = findOne(Criteria.of()
                        .eq("applicationAlias", validApplicationAlias)
                        .eq("alias", validCategoryAlias));
        if (category == null) {
            throw new PlatformException("Dictionary category requires existing category: "
                    + validApplicationAlias + "." + validCategoryAlias);
        }
        if (category.getCategoryKind() != DictionaryCategoryKind.DICTIONARY) {
            throw new PlatformException("Dictionary items require DICTIONARY category: " + validCategoryAlias);
        }
        return category;
    }

    public DictionaryCategory requireDictionaryCategory(String categoryId) {
        String validCategoryId = requireText(categoryId, "dictionaryCategoryId");
        DictionaryCategory category = select(validCategoryId);
        if (category == null) {
            throw new PlatformException("Dictionary category requires existing category: " + validCategoryId);
        }
        if (category.getCategoryKind() != DictionaryCategoryKind.DICTIONARY) {
            throw new PlatformException("Dictionary items require DICTIONARY category: " + validCategoryId);
        }
        return category;
    }

    public DictionaryCategory requireEnabledDictionaryCategory(String applicationAlias, String categoryAlias) {
        DictionaryCategory category = requireDictionaryCategory(applicationAlias, categoryAlias);
        if (!Boolean.TRUE.equals(category.getEnabled())) {
            throw new PlatformException("Dictionary category is disabled: " + categoryAlias);
        }
        return category;
    }

    private void normalizeAndValidate(DictionaryCategory category) {
        String applicationAlias = PlatformNameRules.requireApplicationAlias(category.getApplicationAlias());
        String alias = requireAlias(category.getAlias());
        category.setApplicationAlias(applicationAlias);
        category.setAlias(alias);
        if (category.getCategoryKind() == null) {
            category.setCategoryKind(DictionaryCategoryKind.DICTIONARY);
        }
        rejectDuplicate(category, Criteria.of()
                        .eq("applicationAlias", category.getApplicationAlias())
                        .eq("alias", category.getAlias()),
                "dictionaryCategoryAlias must be unique within application: " + category.getAlias());
        validateParentApplication(category);
    }

    private String requireAlias(String alias) {
        return PlatformNameRules.requireIdentifier(alias, "dictionaryCategoryAlias");
    }

    private void validateParentApplication(DictionaryCategory category) {
        Criteria scope = applicationScope(category.getApplicationAlias());
        validateTreePlacementInScope(category, scope,
                "Dictionary category parent must belong to the same application");
        String parentId = category.getParentId();
        if (parentId == null || parentId.isBlank() || TreeAbility.ROOT_ID.equals(parentId)) {
            return;
        }
        DictionaryCategory parent = selectInScope(scope, parentId);
        if (parent != null && parent.getCategoryKind() != DictionaryCategoryKind.FOLDER) {
            throw new PlatformException("Dictionary category parent must be a folder: " + parentId);
        }
    }

    private void validateImmutableIdentity(DictionaryCategory category) {
        DictionaryCategory existing = selectIncludingDeleted(category.getId());
        rejectChanged(existing, category, "Dictionary category application", DictionaryCategory::getApplicationAlias);
        rejectChanged(existing, category, "Dictionary category alias", DictionaryCategory::getAlias);
        rejectChanged(existing, category, "Dictionary category kind", DictionaryCategory::getCategoryKind);
    }

    private Criteria applicationScope(String applicationAlias) {
        return Criteria.of().eq("applicationAlias", applicationAlias);
    }

    private String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new PlatformException(name + " is required");
        }
        return value.trim();
    }
}
