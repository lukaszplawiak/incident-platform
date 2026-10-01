package com.incidentplatform.auth.repository;

import com.incidentplatform.auth.domain.Tenant;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Tenants as records (backlog #0-80). Writes go only through
 * {@link #insertIfAbsent}; see {@link Tenant} for why not {@code save()}.
 */
@Repository
public interface TenantRepository extends JpaRepository<Tenant, String> {

    /**
     * Inserts the tenant unless its id is taken, atomically: two operators
     * creating the same tenant at once get one 1 and one 0, never two rows and
     * never an overwrite.
     *
     * <p>{@code @Transactional}: joins the caller's transaction (provisioning),
     * and gives a caller without one (the operator tenant's bootstrap) its own
     * read-write transaction; Spring Data's default for query methods is
     * read-only.
     *
     * @return 1 if inserted, 0 if a tenant with this id already exists
     */
    @Transactional
    @Modifying
    @Query(value = """
            INSERT INTO tenants (tenant_id, display_name, first_admin_email, created_at, created_by)
            VALUES (:tenantId, :displayName, :firstAdminEmail, now(), :createdBy)
            ON CONFLICT (tenant_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("tenantId") String tenantId,
                       @Param("displayName") String displayName,
                       @Param("firstAdminEmail") String firstAdminEmail,
                       @Param("createdBy") UUID createdBy);

    Page<Tenant> findAllByOrderByCreatedAtDescTenantIdAsc(Pageable pageable);
}
