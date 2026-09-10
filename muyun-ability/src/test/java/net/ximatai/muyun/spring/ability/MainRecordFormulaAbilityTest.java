package net.ximatai.muyun.spring.ability;

import net.ximatai.muyun.spring.common.exception.PlatformException;
import net.ximatai.muyun.spring.common.formula.FormulaRule;
import net.ximatai.muyun.spring.common.formula.FormulaRuleKind;
import net.ximatai.muyun.spring.common.formula.FormulaRulePhase;
import net.ximatai.muyun.spring.common.formula.FormulaValueType;
import net.ximatai.muyun.spring.common.model.capability.TitledCapable;
import net.ximatai.muyun.spring.common.model.standard.StandardEntity;
import net.ximatai.muyun.spring.ability.reference.ReferenceAbility;
import net.ximatai.muyun.spring.ability.reference.ReferenceCardinality;
import net.ximatai.muyun.spring.ability.reference.ReferencePlan;
import net.ximatai.muyun.spring.ability.reference.ReferenceTarget;
import net.ximatai.muyun.spring.ability.reference.ReferenceTargetResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MainRecordFormulaAbilityTest {
    private static final ReferenceTarget FORMULA_RECORD = ReferenceTarget.of("test", "formula_record");
    private static final ReferenceTarget SUPPLIER = ReferenceTarget.of("test", "supplier");

    @AfterEach
    void resetReferenceTargetResolver() {
        PlatformAbilityRuntime.resetReferenceTargetResolver();
    }

    @Test
    void shouldComputeReverseDeclaredFormulaChainAndDiamondBeforePersisting() {
        FormulaRecordService service = new FormulaRecordService(List.of(
                calculation("gross", "{gross} = {net} + {tax}"),
                calculation("tax", "{tax} = {net} * {taxRate}"),
                calculation("net", "{net} = {amount} * {quantity}")
        ));
        FormulaRecord record = record("10", 4, "0.1");
        record.setNet(new BigDecimal("999"));
        record.setTax(new BigDecimal("999"));
        record.setGross(new BigDecimal("999"));

        String id = service.insert(record);

        assertThat(record.getNet()).isEqualByComparingTo("40.0");
        assertThat(record.getTax()).isEqualByComparingTo("4.0");
        assertThat(record.getGross()).isEqualByComparingTo("44.0");
        assertThat(service.rawDao().findById(id).getGross()).isEqualByComparingTo("44.0");
    }

    @Test
    void shouldRecomputeSubmittedFormulaOutputsAndUseTypedNumberSettersOnUpdate() {
        FormulaRecordService service = new FormulaRecordService(List.of(
                calculation("quantity", "{quantity} = {requestedQuantity} + 1"),
                calculation("net", "{net} = {amount} * {quantity}")
        ));
        FormulaRecord created = record("7.5", 1, "0");
        created.setRequestedQuantity(2);
        String id = service.insert(created);

        FormulaRecord update = record("7.5", 999, "0");
        update.setId(id);
        update.setVersion(created.getVersion());
        update.setRequestedQuantity(3);
        update.setNet(new BigDecimal("12345"));

        assertThat(service.update(update)).isEqualTo(1);
        assertThat(update.getQuantity()).isEqualTo(4);
        assertThat(update.getNet()).isEqualByComparingTo("30.0");
        assertThat(service.rawDao().findById(id).getNet()).isInstanceOf(BigDecimal.class);
    }

    @Test
    void shouldBlockPersistenceAndLeaveEntityUntouchedWhenFormulaValidationFails() {
        FormulaRecordService service = new FormulaRecordService(List.of(
                calculation("net", "{net} = {amount} * {quantity}"),
                new FormulaRule("grossLimit", "{net} <= 10", FormulaRuleKind.VALIDATION,
                        FormulaRulePhase.BEFORE_SAVE, "net")
        ));
        FormulaRecord record = record("10", 2, "0");
        record.setNet(new BigDecimal("777"));

        assertThatThrownBy(() -> service.insert(record))
                .isInstanceOf(PlatformException.class)
                .extracting(error -> ((PlatformException) error).code())
                .isEqualTo("FORMULA_SAVE_VALIDATION_FAILED");

        assertThat(record.getNet()).isEqualByComparingTo("777");
        assertThat(service.rawDao().count(net.ximatai.muyun.database.core.orm.Criteria.of())).isZero();
    }

    @Test
    void shouldRejectProtectedFieldsAndChildInputsInsteadOfPretendingToSupportThem() {
        FormulaRecord protectedRecord = record("1", 1, "0");
        FormulaRecordService protectedService = new FormulaRecordService(List.of(
                calculation("id", "{id} = 'forbidden'")
        ));
        assertThatThrownBy(() -> protectedService.insert(protectedRecord))
                .isInstanceOf(PlatformException.class)
                .extracting(error -> ((PlatformException) error).code())
                .isEqualTo("FORMULA_STATIC_PROTECTED_FIELD");

        FormulaRecordService childInputService = new FormulaRecordService(List.of(
                calculation("gross", "SUM({lines.amount})")
        ));
        assertThatThrownBy(() -> childInputService.insert(record("1", 1, "0")))
                .isInstanceOf(PlatformException.class)
                .extracting(error -> ((PlatformException) error).code())
                .isEqualTo("FORMULA_PLAN_UNKNOWN_FIELD");
    }

    @Test
    void shouldRejectNonBeforeSavePhaseAndLeaveOrdinaryStaticServicesUnaffected() {
        FormulaRecordService wrongPhase = new FormulaRecordService(List.of(
                new FormulaRule("default", "{net} = 1", FormulaRuleKind.CALCULATION,
                        FormulaRulePhase.DEFAULT_VALUE, "net")
        ));
        assertThatThrownBy(() -> wrongPhase.insert(record("1", 1, "0")))
                .isInstanceOf(PlatformException.class)
                .extracting(error -> ((PlatformException) error).code())
                .isEqualTo("FORMULA_STATIC_PHASE_UNSUPPORTED");

        UnoptedFormulaRecordService ordinary = new UnoptedFormulaRecordService();
        FormulaRecord record = record("10", 2, "0");
        record.setNet(new BigDecimal("777"));
        ordinary.insert(record);

        assertThat(record.getNet()).isEqualByComparingTo("777");
    }

    @Test
    void shouldRejectNarrowingBeforeWritingAnyCalculatedProperty() {
        FormulaRecordService byteOverflow = new FormulaRecordService(List.of(
                calculation("first", "{first} = 1"),
                calculation("byteValue", "{byteValue} = 128")
        ));
        FormulaRecord byteRecord = record("1", 1, "0");
        byteRecord.setFirst(777);

        assertThatThrownBy(() -> byteOverflow.insert(byteRecord))
                .isInstanceOf(PlatformException.class)
                .extracting(error -> ((PlatformException) error).code())
                .isEqualTo("FORMULA_STATIC_NUMERIC_NARROWING_FAILED");
        assertThat(byteRecord.getFirst()).isEqualTo(777);
        assertThat(byteOverflow.rawDao().count(net.ximatai.muyun.database.core.orm.Criteria.of())).isZero();

        FormulaRecordService shortOverflow = new FormulaRecordService(List.of(
                calculation("shortValue", "{shortValue} = 32768")
        ));
        assertThatThrownBy(() -> shortOverflow.insert(record("1", 1, "0")))
                .isInstanceOf(PlatformException.class)
                .extracting(error -> ((PlatformException) error).code())
                .isEqualTo("FORMULA_STATIC_NUMERIC_NARROWING_FAILED");

        FormulaRecordService floatOverflow = new FormulaRecordService(List.of(
                calculation("ratio", "{ratio} = 1e39")
        ));
        assertThatThrownBy(() -> floatOverflow.insert(record("1", 1, "0")))
                .isInstanceOf(PlatformException.class)
                .extracting(error -> ((PlatformException) error).code())
                .isEqualTo("FORMULA_STATIC_NUMERIC_NARROWING_FAILED");
    }

    @Test
    void shouldResolveReferenceInputsWithoutReadingOrOverwritingSameNamedLocalProperties() {
        PlatformAbilityRuntime.configureReferenceTargetResolver(formulaReferenceResolver());
        FormulaRecordService service = new FormulaRecordService(List.of(
                calculation("gross", "{gross} = {amount} + {supplierId.rate} + {supplierId.externalRate}")
        ));
        FormulaRecord record = record("10", 1, "0");
        record.setSupplierId("supplier-1");
        record.setRate(new BigDecimal("100"));

        String id = service.insert(record);

        assertThat(record.getGross()).isEqualByComparingTo("20");
        assertThat(record.getRate()).isEqualByComparingTo("100");
        assertThat(service.rawDao().findById(id).getGross()).isEqualByComparingTo("20");
        assertThat(service.rawDao().findById(id).getRate()).isEqualByComparingTo("100");
    }

    private static FormulaRule calculation(String target, String expression) {
        return new FormulaRule(target, expression, FormulaRuleKind.CALCULATION, FormulaRulePhase.BEFORE_SAVE, target);
    }

    private static FormulaRecord record(String amount, int quantity, String taxRate) {
        FormulaRecord record = new FormulaRecord();
        record.setAmount(new BigDecimal(amount));
        record.setQuantity(quantity);
        record.setTaxRate(new BigDecimal(taxRate));
        return record;
    }

    private static ReferenceTargetResolver formulaReferenceResolver() {
        return new ReferenceTargetResolver() {
            @Override
            public Optional<ReferenceAbility<?>> resolve(ReferenceTarget target) {
                if (!SUPPLIER.equals(target)) {
                    return Optional.empty();
                }
                return Optional.of(new ReferenceAbility<ReferenceFormulaTarget>() {
                    @Override
                    public BaseDao<ReferenceFormulaTarget, String> getDao() {
                        return null;
                    }

                    @Override
                    public String getModuleAlias() {
                        return target.qualifiedName();
                    }

                    @Override
                    public Map<String, Map<String, Object>> projections(java.util.Collection<String> ids,
                                                                          java.util.Collection<String> fieldNames) {
                        Map<String, Map<String, Object>> projections = new LinkedHashMap<>();
                        for (String id : ids) {
                            projections.put(id, Map.of("rate", new BigDecimal("7"),
                                    "externalRate", new BigDecimal("3")));
                        }
                        return projections;
                    }
                });
            }

            @Override
            public Optional<ReferencePlan> referencePlan(ReferenceTarget source, String field) {
                return FORMULA_RECORD.equals(source) && "supplierId".equals(field)
                        ? Optional.of(ReferencePlan.of(field, SUPPLIER, ReferenceCardinality.ONE))
                        : Optional.empty();
            }

            @Override
            public Optional<FormulaValueType> formulaFieldType(ReferenceTarget target, String field) {
                if (FORMULA_RECORD.equals(target) && "supplierId".equals(field)) {
                    return Optional.of(FormulaValueType.STRING);
                }
                if (SUPPLIER.equals(target) && ("rate".equals(field) || "externalRate".equals(field))) {
                    return Optional.of(FormulaValueType.DECIMAL);
                }
                return Optional.empty();
            }
        };
    }

    private static final class FormulaRecord extends StandardEntity {
        private BigDecimal amount;
        private Integer quantity;
        private Integer requestedQuantity;
        private BigDecimal taxRate;
        private BigDecimal net;
        private BigDecimal tax;
        private BigDecimal gross;
        private String supplierId;
        private BigDecimal rate;
        private Integer first;
        private Byte byteValue;
        private Short shortValue;
        private Float ratio;

        public BigDecimal getAmount() { return amount; }
        public void setAmount(BigDecimal amount) { this.amount = amount; }
        public Integer getQuantity() { return quantity; }
        public void setQuantity(Integer quantity) { this.quantity = quantity; }
        public Integer getRequestedQuantity() { return requestedQuantity; }
        public void setRequestedQuantity(Integer requestedQuantity) { this.requestedQuantity = requestedQuantity; }
        public BigDecimal getTaxRate() { return taxRate; }
        public void setTaxRate(BigDecimal taxRate) { this.taxRate = taxRate; }
        public BigDecimal getNet() { return net; }
        public void setNet(BigDecimal net) { this.net = net; }
        public BigDecimal getTax() { return tax; }
        public void setTax(BigDecimal tax) { this.tax = tax; }
        public BigDecimal getGross() { return gross; }
        public void setGross(BigDecimal gross) { this.gross = gross; }
        public String getSupplierId() { return supplierId; }
        public void setSupplierId(String supplierId) { this.supplierId = supplierId; }
        public BigDecimal getRate() { return rate; }
        public void setRate(BigDecimal rate) { this.rate = rate; }
        public Integer getFirst() { return first; }
        public void setFirst(Integer first) { this.first = first; }
        public Byte getByteValue() { return byteValue; }
        public void setByteValue(Byte byteValue) { this.byteValue = byteValue; }
        public Short getShortValue() { return shortValue; }
        public void setShortValue(Short shortValue) { this.shortValue = shortValue; }
        public Float getRatio() { return ratio; }
        public void setRatio(Float ratio) { this.ratio = ratio; }
    }

    private static final class FormulaRecordService extends AbstractAbilityService<FormulaRecord>
            implements MainRecordFormulaAbility<FormulaRecord> {
        private final List<FormulaRule> rules;

        private FormulaRecordService(List<FormulaRule> rules) {
            super("test.formula_record", FormulaRecord.class, new InMemoryBaseDao<>());
            this.rules = rules;
        }

        @Override
        public List<FormulaRule> mainRecordFormulaRules() {
            return rules;
        }

        @SuppressWarnings("unchecked")
        InMemoryBaseDao<FormulaRecord> rawDao() {
            return (InMemoryBaseDao<FormulaRecord>) getDao();
        }
    }

    private static final class UnoptedFormulaRecordService extends AbstractAbilityService<FormulaRecord> {
        private UnoptedFormulaRecordService() {
            super("test.unopted_formula_record", FormulaRecord.class, new InMemoryBaseDao<>());
        }

        List<FormulaRule> mainRecordFormulaRules() {
            return List.of(calculation("net", "{net} = {amount} * {quantity}"));
        }
    }

    private static final class ReferenceFormulaTarget extends StandardEntity implements TitledCapable {
        @Override
        public String getTitle() {
            return "supplier";
        }
    }
}
