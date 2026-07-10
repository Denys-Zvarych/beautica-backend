package com.beautica.service.repository;

import com.beautica.AbstractDataJpaTest;
import com.beautica.auth.Role;
import com.beautica.salon.entity.Salon;
import com.beautica.service.entity.CatalogCategory;
import com.beautica.service.entity.OwnerType;
import com.beautica.service.entity.PriceType;
import com.beautica.service.entity.ServiceDefinition;
import com.beautica.service.entity.ServiceType;
import com.beautica.user.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceRepositoryTest extends AbstractDataJpaTest {

    @Autowired
    private ServiceRepository serviceRepository;

    @Autowired
    private TestEntityManager em;

    private UUID salonOwnerId;
    private ServiceType defaultServiceType;

    @BeforeEach
    void setUp() {
        User owner = new User(
                "salon-owner-" + UUID.randomUUID() + "@example.com",
                "$2a$10$hashedpassword",
                Role.SALON_OWNER,
                "Salon",
                "Owner",
                "+380501234567"
        );
        em.persist(owner);

        Salon salon = Salon.builder()
                .owner(owner)
                .name("Beauty Studio")
                .isActive(true)
                .build();
        em.persist(salon);

        // service_type_id is mandatory (NOT NULL). Persist one shared ServiceType and
        // attach it to every fixture ServiceDefinition via the builder.
        defaultServiceType = persistServiceType();

        em.flush();

        salonOwnerId = salon.getId();
    }

    /** Persists a CatalogCategory + ServiceType so fixture ServiceDefinitions satisfy the NOT NULL service_type_id FK. */
    private ServiceType persistServiceType() {
        CatalogCategory category = CatalogCategory.builder()
                .nameUk("Нігті")
                .nameEn("Nails")
                .sortOrder(999)
                .build();
        em.persist(category);

        ServiceType serviceType = ServiceType.builder()
                .category(category)
                .nameUk("Манікюр")
                .nameEn("Manicure")
                .slug("type-" + UUID.randomUUID())
                .platformCategoryName("NAIL_SERVICE")
                .build();
        em.persist(serviceType);
        return serviceType;
    }

    @Test
    @DisplayName("should_returnActiveServices_when_ownerTypeAndOwnerIdMatch")
    void should_returnActiveServices_when_ownerTypeAndOwnerIdMatch() {
        ServiceDefinition activeService = ServiceDefinition.builder()
                .ownerType(OwnerType.SALON)
                .ownerId(salonOwnerId)
                .name("Gel Manicure")
                .category("MANICURE")
                .baseDurationMinutes(60)
                .priceType(PriceType.FIXED)
                .basePrice(new BigDecimal("550.00"))
                .serviceType(defaultServiceType)
                .isActive(true)
                .build();
        em.persist(activeService);

        ServiceDefinition inactiveService = ServiceDefinition.builder()
                .ownerType(OwnerType.SALON)
                .ownerId(salonOwnerId)
                .name("Classic Pedicure")
                .category("PEDICURE")
                .baseDurationMinutes(75)
                .priceType(PriceType.FIXED)
                .basePrice(new BigDecimal("400.00"))
                .serviceType(defaultServiceType)
                .isActive(false)
                .build();
        em.persist(inactiveService);

        em.flush();

        List<ServiceDefinition> results =
                serviceRepository.findByOwnerTypeAndOwnerIdAndIsActiveTrue(OwnerType.SALON, salonOwnerId);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getId()).isEqualTo(activeService.getId());
    }

    @Test
    @DisplayName("should_returnEmpty_when_ownerTypeDoesNotMatch")
    void should_returnEmpty_when_ownerTypeDoesNotMatch() {
        ServiceDefinition salonService = ServiceDefinition.builder()
                .ownerType(OwnerType.SALON)
                .ownerId(salonOwnerId)
                .name("Eyebrow Shaping")
                .category("BROWS")
                .baseDurationMinutes(30)
                .priceType(PriceType.FIXED)
                .basePrice(new BigDecimal("200.00"))
                .serviceType(defaultServiceType)
                .isActive(true)
                .build();
        em.persist(salonService);

        em.flush();

        List<ServiceDefinition> results =
                serviceRepository.findByOwnerTypeAndOwnerIdAndIsActiveTrue(OwnerType.INDEPENDENT_MASTER, salonOwnerId);

        assertThat(results).isEmpty();
    }

    @Test
    @DisplayName("should_notLeakAcrossOwnerTypes_when_ownerIdCoincides")
    void should_notLeakAcrossOwnerTypes_when_ownerIdCoincides() {
        ServiceDefinition salonService = ServiceDefinition.builder()
                .ownerType(OwnerType.SALON)
                .ownerId(salonOwnerId)
                .name("Gel Manicure")
                .category("MANICURE")
                .baseDurationMinutes(60)
                .priceType(PriceType.FIXED)
                .basePrice(new BigDecimal("550.00"))
                .serviceType(defaultServiceType)
                .isActive(true)
                .build();
        em.persist(salonService);

        ServiceDefinition independentMasterService = ServiceDefinition.builder()
                .ownerType(OwnerType.INDEPENDENT_MASTER)
                .ownerId(salonOwnerId)
                .name("Eyelash Extensions")
                .category("EYELASH")
                .baseDurationMinutes(120)
                .priceType(PriceType.FIXED)
                .basePrice(new BigDecimal("900.00"))
                .serviceType(defaultServiceType)
                .isActive(true)
                .build();
        em.persist(independentMasterService);

        em.flush();

        List<ServiceDefinition> salonResults =
                serviceRepository.findByOwnerTypeAndOwnerIdAndIsActiveTrue(OwnerType.SALON, salonOwnerId);

        List<ServiceDefinition> masterResults =
                serviceRepository.findByOwnerTypeAndOwnerIdAndIsActiveTrue(OwnerType.INDEPENDENT_MASTER, salonOwnerId);

        assertThat(salonResults).hasSize(1);
        assertThat(salonResults).extracting(ServiceDefinition::getId).containsExactly(salonService.getId());

        assertThat(masterResults).hasSize(1);
        assertThat(masterResults).extracting(ServiceDefinition::getId).containsExactly(independentMasterService.getId());
    }
}
