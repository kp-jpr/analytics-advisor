package gov.rajasthan.smart.srse.metadata;

import gov.rajasthan.smart.srse.compiler.CompareAs;
import gov.rajasthan.smart.srse.lakehouse.LakehouseBrowseService;
import gov.rajasthan.smart.srse.lakehouse.LakehouseRegistryService;
import gov.rajasthan.smart.srse.lakehouse.QualifiedColumn;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

/**
 * Admin API for the Analysis tab's per-column overrides — business name,
 * fuzzy-matchable, and visibility — keyed by the full
 * {@code catalog.schema.table.column} address. See
 * {@link AnalysisColumnMetadata}'s javadoc for why this is separate from the
 * Rule Engine's field catalogue, and why the key had to become qualified.
 */
@RestController
@RequestMapping("/api/analysis/column-metadata")
public class AnalysisColumnMetadataController {

    private final AnalysisColumnMetadataRepository repository;
    private final LakehouseRegistryService registry;
    private final LakehouseBrowseService browse;

    public AnalysisColumnMetadataController(AnalysisColumnMetadataRepository repository,
                                            LakehouseRegistryService registry,
                                            LakehouseBrowseService browse) {
        this.repository = repository;
        this.registry = registry;
        this.browse = browse;
    }

    @GetMapping
    public List<ColumnMetadataResponse> list() {
        return repository.findAllByOrderByCatalogNameAscSchemaNameAscTableNameAscColumnNameAsc()
                .stream().map(ColumnMetadataResponse::from).toList();
    }

    @PutMapping
    public ColumnMetadataResponse upsert(@RequestBody UpsertColumnMetadataRequest req) {
        // Two checks, deliberately NOT registry.validateColumn(): that one
        // filters hidden columns out, so it would reject the very request
        // that flips a hidden column back to visible. Admin needs to address
        // any real column of a registered table, hidden or not.
        registry.validateRegistered(req.catalog(), req.schema(), req.table());
        browse.validateColumn(req.catalog(), req.schema(), req.table(), req.column());

        Optional<AnalysisColumnMetadata> existing = repository
                .findByCatalogNameAndSchemaNameAndTableNameAndColumnName(
                        req.catalog(), req.schema(), req.table(), req.column());
        Long existingId = existing.map(AnalysisColumnMetadata::getId).orElse(null);
        AnalysisColumnMetadata entity = new AnalysisColumnMetadata(
                existingId, new QualifiedColumn(req.catalog(), req.schema(), req.table(), req.column()),
                req.businessName(), req.fuzzyMatchable(), req.visible());
        entity.setCompareAs(req.compareAs());
        // This PUT rebuilds the whole row, so an absent flag must keep what is stored — otherwise
        // any client that predates the field would silently switch a picker off on every save.
        entity.setValuePickerEnabled(req.valuePickerEnabled() != null
                ? req.valuePickerEnabled()
                : existing.map(AnalysisColumnMetadata::isValuePickerEnabled).orElse(false));
        entity.setMaskPolicy(req.maskPolicy() != null
                ? req.maskPolicy()
                : existing.map(AnalysisColumnMetadata::getMaskPolicy).orElse(ColumnMaskPolicy.NONE));
        entity.setMaskKeepLast(req.maskKeepLast() != null
                ? req.maskKeepLast()
                : existing.map(AnalysisColumnMetadata::getMaskKeepLast).orElse(4));
        return ColumnMetadataResponse.from(repository.save(entity));
    }

    /**
     * Drops the override, reverting the column to its defaults — visible, with
     * an auto-derived label and a name-substring guess for fuzzy eligibility.
     *
     * <p>Deliberately NOT gated on the table still being registered. Curated
     * rows outlive an unregister (so re-registering restores the admin's
     * intent — including the columns they had hidden), which means the orphans
     * this cleans up are exactly the ones whose table is no longer registered.
     * Idempotent: deleting an override that isn't there is a no-op, so a
     * double-click cannot 404.
     */
    @DeleteMapping
    public void delete(@RequestParam String catalog, @RequestParam String schema,
                       @RequestParam String table, @RequestParam String column) {
        repository.findByCatalogNameAndSchemaNameAndTableNameAndColumnName(catalog, schema, table, column)
                .ifPresent(repository::delete);
    }

    public record UpsertColumnMetadataRequest(String catalog, String schema, String table, String column,
                                              String businessName, boolean fuzzyMatchable, Boolean visible,
                                              CompareAs compareAs, Boolean valuePickerEnabled,
                                              ColumnMaskPolicy maskPolicy, Integer maskKeepLast) {
        public UpsertColumnMetadataRequest(String catalog, String schema, String table, String column,
                                           String businessName, boolean fuzzyMatchable, Boolean visible,
                                           CompareAs compareAs) {
            this(catalog, schema, table, column, businessName, fuzzyMatchable, visible, compareAs, null, null, null);
        }

        public UpsertColumnMetadataRequest(String catalog, String schema, String table, String column,
                                           String businessName, boolean fuzzyMatchable, Boolean visible,
                                           CompareAs compareAs, Boolean valuePickerEnabled) {
            this(catalog, schema, table, column, businessName, fuzzyMatchable, visible, compareAs,
                    valuePickerEnabled, null, null);
        }

        /**
         * Null {@code visible} reads as visible and null {@code compareAs} as
         * AUTO — see {@link AnalysisColumnMetadata}. Normalised here so a
         * client that predates either field still round-trips unchanged.
         */
        public UpsertColumnMetadataRequest {
            visible = !Boolean.FALSE.equals(visible);
            compareAs = CompareAs.orAuto(compareAs);
        }
    }

    public record ColumnMetadataResponse(String catalog, String schema, String table, String column,
                                         String businessName, boolean fuzzyMatchable, boolean visible,
                                         CompareAs compareAs, boolean valuePickerEnabled,
                                         ColumnMaskPolicy maskPolicy, int maskKeepLast) {
        static ColumnMetadataResponse from(AnalysisColumnMetadata entity) {
            return new ColumnMetadataResponse(
                    entity.getCatalogName(), entity.getSchemaName(), entity.getTableName(),
                    entity.getColumnName(), entity.getBusinessName(),
                    entity.isFuzzyMatchable(), entity.isVisible(), entity.getCompareAs(),
                    entity.isValuePickerEnabled(), entity.getMaskPolicy(), entity.getMaskKeepLast());
        }
    }
}
