package net.ximatai.muyun.spring.demo.school.subject;

import lombok.Getter;
import lombok.Setter;
import net.ximatai.muyun.database.core.annotation.Column;
import net.ximatai.muyun.database.core.annotation.Table;
import net.ximatai.muyun.database.core.builder.ColumnType;
import net.ximatai.muyun.spring.common.model.standard.StandardEnabledTreeEntity;
import net.ximatai.muyun.spring.common.model.constraint.TenantUniqueConstraint;

/**
 * 教学学科分类树。
 *
 * <p>分类是教学评价的稳定引用目标：教师归属一个授课学科，动态测评也引用同一分类，
 * 而不是在各自模块复制“数学”“英语”等文本。</p>
 */
@Getter
@Setter
@Table(name = "education_subject_category", comment = "学科分类")
@TenantUniqueConstraint(fields = "code", message = "subject category code already exists in the current tenant")
public class SubjectCategory extends StandardEnabledTreeEntity {
    @Column(name = "code", type = ColumnType.VARCHAR, length = 32, nullable = false)
    private String code;

}
