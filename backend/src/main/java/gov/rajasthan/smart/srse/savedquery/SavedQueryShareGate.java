package gov.rajasthan.smart.srse.savedquery;

import gov.rajasthan.smart.srse.analysis.AdditionalJoin;
import gov.rajasthan.smart.srse.analysis.ComparisonGroup;
import gov.rajasthan.smart.srse.analysis.DisplayColumn;
import gov.rajasthan.smart.srse.analysis.MatchCriterion;
import gov.rajasthan.smart.srse.analysis.MatchGroup;
import gov.rajasthan.smart.srse.analysis.RecordMatchRequest;
import gov.rajasthan.smart.srse.identity.AdminAccessDeniedException;
import gov.rajasthan.smart.srse.identity.AppUser;
import gov.rajasthan.smart.srse.lakehouse.AnalysisScopeFromService;
import gov.rajasthan.smart.srse.lakehouse.OfficerRegistryScopeService;
import gov.rajasthan.smart.srse.lakehouse.QualifiedTable;
import gov.rajasthan.smart.srse.lakehouse.RegisteredTable;
import gov.rajasthan.smart.srse.lakehouse.RegisteredTableRepository;
import gov.rajasthan.smart.srse.lakehouse.RegisteredTableScopeCatalog;
import gov.rajasthan.smart.srse.lakehouse.ScopeFilteredFrom;
import gov.rajasthan.smart.srse.lakehouse.TableScopePolicy;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Ensures a grantee could run the saved request under their own scope (AA-18). */
@Component
public class SavedQueryShareGate {

    private final OfficerRegistryScopeService officerScope;
    private final RegisteredTableRepository registeredTableRepository;
    private final RegisteredTableScopeCatalog scopeCatalog;
    private final AnalysisScopeFromService scopeFrom;

    public SavedQueryShareGate(
            OfficerRegistryScopeService officerScope,
            RegisteredTableRepository registeredTableRepository,
            RegisteredTableScopeCatalog scopeCatalog,
            AnalysisScopeFromService scopeFrom) {
        this.officerScope = officerScope;
        this.registeredTableRepository = registeredTableRepository;
        this.scopeCatalog = scopeCatalog;
        this.scopeFrom = scopeFrom;
    }

    public void assertGranteeCanRun(RecordMatchRequest request, AppUser grantee) {
        TableScopePolicy.OfficerScopeView view = officerScope.officerScopeFor(grantee);
        for (QualifiedTable table : tablesReferenced(request)) {
            RegisteredTable row = registeredTableRepository
                    .findByCatalogNameAndSchemaNameAndTableName(
                            table.catalog(), table.schema(), table.table())
                    .orElseThrow(() -> new AdminAccessDeniedException(
                            "Recipient cannot reach unregistered table " + table.qualifiedName()));
            TableScopePolicy.TableScopeMetadata metadata = scopeCatalog.metadataFor(row);
            if (!TableScopePolicy.isTableVisible(view, metadata)) {
                throw new AdminAccessDeniedException(
                        "Recipient's scope does not cover table " + table.qualifiedName());
            }
            ScopeFilteredFrom planned = scopeFrom.planFrom(view, table.qualifiedName(), metadata);
            if (planned.isEmptyResult()) {
                throw new AdminAccessDeniedException(
                        "Recipient's scope would return no rows from " + table.qualifiedName());
            }
        }
    }

    private static Set<QualifiedTable> tablesReferenced(RecordMatchRequest request) {
        Set<QualifiedTable> tables = new LinkedHashSet<>();
        addCriteriaTables(tables, request.sourceCriteria());
        addCriteriaTables(tables, request.targetCriteria());
        addDisplayTables(tables, request.sourceDisplayColumns());
        addDisplayTables(tables, request.targetDisplayColumns());
        addDisplayTables(tables, request.groupByColumns());
        for (MatchGroup group : request.joinGroups()) {
            addCriteriaTables(tables, group.source());
            addCriteriaTables(tables, group.target());
        }
        for (ComparisonGroup group : request.comparisonGroups()) {
            addCriteriaTables(tables, group.source());
            addCriteriaTables(tables, group.target());
        }
        for (AdditionalJoin extra : request.additionalJoins()) {
            tables.add(extra.qualifiedTable());
        }
        return tables;
    }

    private static void addCriteriaTables(Set<QualifiedTable> out, List<MatchCriterion> criteria) {
        if (criteria == null) {
            return;
        }
        for (MatchCriterion c : criteria) {
            out.add(new QualifiedTable(c.catalog(), c.schema(), c.table()));
        }
    }

    private static void addDisplayTables(Set<QualifiedTable> out, List<DisplayColumn> columns) {
        if (columns == null) {
            return;
        }
        for (DisplayColumn c : columns) {
            out.add(new QualifiedTable(c.catalog(), c.schema(), c.table()));
        }
    }
}
