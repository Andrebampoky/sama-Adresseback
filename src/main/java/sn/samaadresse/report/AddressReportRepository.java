package sn.samaadresse.report;

import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AddressReportRepository extends JpaRepository<AddressReport, UUID> {
    boolean existsByAddressIdAndReporterIdAndStatus(UUID addressId, UUID reporterId, ReportStatus status);

    Page<AddressReport> findByStatus(ReportStatus status, Pageable pageable);

    List<AddressReport> findByAddressIdAndStatus(UUID addressId, ReportStatus status);

    long countByStatus(ReportStatus status);
}
