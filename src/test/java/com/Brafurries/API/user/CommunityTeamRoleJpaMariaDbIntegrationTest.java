package com.Brafurries.API.user;

import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.community.CommunityTeamRole;
import com.Brafurries.API.entity.community.CommunityTeamDemand;
import com.Brafurries.API.entity.community.CommunityTeamDemandRole;
import com.Brafurries.API.repository.community.CommunityTeamDemandRepository;
import com.Brafurries.API.repository.community.CommunityTeamDemandRoleRepository;
import com.Brafurries.API.repository.community.CommunityTeamRoleRepository;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in integration coverage for the disposable MariaDB harness only. The
 * required datasource settings are intentionally supplied at execution time,
 * so this test cannot fall back to a local application configuration.
 */
@EnabledIfEnvironmentVariable(named = "BRF_COMMUNITY_TEAM_ROLES_INTEGRATION", matches = "true")
@DataJpaTest(properties = {
    "spring.datasource.url=${BRF_DISPOSABLE_JDBC_URL}",
    "spring.datasource.username=${BRF_DISPOSABLE_DB_USER}",
    "spring.datasource.password=${BRF_DISPOSABLE_DB_PASSWORD}",
    "spring.jpa.hibernate.ddl-auto=none"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CommunityTeamRoleJpaMariaDbIntegrationTest {

    @Autowired
    private CommunityTeamRoleRepository roleRepository;
    @Autowired private CommunityTeamDemandRepository demandRepository;
    @Autowired private CommunityTeamDemandRoleRepository demandRoleRepository;

    @Autowired
    private TestEntityManager entityManager;

    @Test
    void persistsAndUpdatesTheRoleTreeWithoutHibernateSchemaManagement() {
        Community community = new Community();
        community.setName("Community A");
        entityManager.persistAndFlush(community);

        CommunityTeamRole owner = role("Dono", community, null);
        roleRepository.saveAndFlush(owner);

        CommunityTeamRole coordinator = role("Coordenador", community, owner);
        roleRepository.saveAndFlush(coordinator);

        entityManager.clear();
        CommunityTeamRole persistedCoordinator = roleRepository.findById(coordinator.getId()).orElseThrow();
        assertEquals(owner.getId(), persistedCoordinator.getParentRole().getId());
        assertEquals(community.getId(), persistedCoordinator.getCommunity().getId());
        assertNotNull(persistedCoordinator.getCreatedAt());
        assertNotNull(persistedCoordinator.getUpdatedAt());

        CommunityTeamRole alternateParent = role("Gestor", community, null);
        roleRepository.saveAndFlush(alternateParent);
        LocalDateTime beforeParentUpdate = persistedCoordinator.getUpdatedAt().minusSeconds(1);
        persistedCoordinator.setUpdatedAt(beforeParentUpdate);
        persistedCoordinator.setParentRole(alternateParent);
        CommunityTeamRole parentUpdated = roleRepository.saveAndFlush(persistedCoordinator);
        assertTrue(parentUpdated.getUpdatedAt().isAfter(beforeParentUpdate));

        entityManager.clear();
        CommunityTeamRole reparented = roleRepository.findById(coordinator.getId()).orElseThrow();
        assertEquals(alternateParent.getId(), reparented.getParentRole().getId());

        reparented.setParentRole(null);
        LocalDateTime beforeActiveUpdate = reparented.getUpdatedAt().minusSeconds(1);
        reparented.setUpdatedAt(beforeActiveUpdate);
        reparented.setActive(false);
        CommunityTeamRole activeUpdated = roleRepository.saveAndFlush(reparented);
        assertTrue(activeUpdated.getUpdatedAt().isAfter(beforeActiveUpdate));

        entityManager.clear();
        CommunityTeamRole detached = roleRepository.findById(coordinator.getId()).orElseThrow();
        assertNull(detached.getParentRole());
        assertEquals(Boolean.FALSE, detached.getActive());
        assertTrue(!detached.getUpdatedAt().isBefore(detached.getCreatedAt()));
    }

    @Test
    void persistsDemandLinksAndRemovesOnlyAssociationRows() {
        Community community = new Community(); community.setName("Community Demand"); entityManager.persistAndFlush(community);
        CommunityTeamRole role = role("Moderador", community, null); roleRepository.saveAndFlush(role);
        CommunityTeamRole secondRole = role("Recepção", community, null); roleRepository.saveAndFlush(secondRole);
        CommunityTeamDemand demand = new CommunityTeamDemand(); demand.setCommunity(community); demand.setName("Portaria");
        demandRepository.saveAndFlush(demand);
        demandRoleRepository.saveAndFlush(new CommunityTeamDemandRole(community.getId(), demand.getId(), role.getId()));
        assertEquals(List.of(role.getId()), demandRoleRepository.findByCommunityIdAndDemandIdOrderByRoleIdAsc(community.getId(), demand.getId()).stream().map(CommunityTeamDemandRole::getRoleId).toList());
        assertNotNull(demand.getCreatedAt()); assertNotNull(demand.getUpdatedAt());
        LocalDateTime beforeUpdate = demand.getUpdatedAt().minusSeconds(1); demand.setUpdatedAt(beforeUpdate); demand.setDescription("Responsabilidade contínua"); demandRepository.saveAndFlush(demand);
        assertTrue(demand.getUpdatedAt().isAfter(beforeUpdate));

        demandRoleRepository.deleteByCommunityIdAndDemandId(community.getId(), demand.getId());
        demandRoleRepository.saveAll(List.of(new CommunityTeamDemandRole(community.getId(), demand.getId(), role.getId()), new CommunityTeamDemandRole(community.getId(), demand.getId(), secondRole.getId())));
        entityManager.flush(); entityManager.clear();
        assertEquals(List.of(role.getId(), secondRole.getId()), demandRoleRepository.findByCommunityIdAndDemandIdOrderByRoleIdAsc(community.getId(), demand.getId()).stream().map(CommunityTeamDemandRole::getRoleId).toList());

        roleRepository.deleteById(role.getId()); entityManager.flush(); entityManager.clear();
        assertTrue(demandRepository.findById(demand.getId()).isPresent());
        assertEquals(List.of(secondRole.getId()), demandRoleRepository.findByCommunityIdAndDemandIdOrderByRoleIdAsc(community.getId(), demand.getId()).stream().map(CommunityTeamDemandRole::getRoleId).toList());
        demandRepository.deleteById(demand.getId()); entityManager.flush(); entityManager.clear();
        assertTrue(roleRepository.findById(secondRole.getId()).isPresent());
    }

    private CommunityTeamRole role(String name, Community community, CommunityTeamRole parent) {
        CommunityTeamRole role = new CommunityTeamRole();
        role.setName(name);
        role.setCommunity(community);
        role.setParentRole(parent);
        role.setActive(true);
        return role;
    }
}
