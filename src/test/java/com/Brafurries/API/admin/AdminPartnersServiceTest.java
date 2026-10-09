package com.Brafurries.API.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.Brafurries.API.admin.dto.AdminDtos.AdminPartnerItem;
import com.Brafurries.API.admin.dto.AdminDtos.CreatePartnerRequest;
import com.Brafurries.API.admin.dto.AdminDtos.PartnerExternalLinkItem;
import com.Brafurries.API.admin.dto.AdminDtos.PartnerLinkedResource;
import com.Brafurries.API.admin.dto.AdminDtos.PartnerLinkCandidate;
import com.Brafurries.API.admin.dto.AdminDtos.UpdatePartnerRequest;
import com.Brafurries.API.config.CacheConfig;
import com.Brafurries.API.entity.misc.Partner;
import com.Brafurries.API.entity.misc.PartnerExternalLink;
import com.Brafurries.API.entity.misc.PartnerLink;
import com.Brafurries.API.entity.community.CommunityDiscord;
import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.event.Event;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserDiscord;
import com.Brafurries.API.entity.user.UserTelegram;
import com.Brafurries.API.partner.PartnerImageStorageService;
import com.Brafurries.API.partner.PartnerImageTransactionCleanup;
import com.Brafurries.API.partner.PartnerSlugService;
import com.Brafurries.API.repository.community.CommunityRepository;
import com.Brafurries.API.repository.community.CommunityDiscordRepository;
import com.Brafurries.API.repository.event.EventRepository;
import com.Brafurries.API.repository.misc.PartnerMetricRepository;
import com.Brafurries.API.repository.misc.PartnerMetricRepository.PartnerMetricTotalsProjection;
import com.Brafurries.API.repository.misc.PartnerRepository;
import com.Brafurries.API.repository.misc.PartnerTokenRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.repository.user.UserDiscordRepository;
import com.Brafurries.API.repository.user.UserTelegramRepository;
import jakarta.persistence.CascadeType;
import jakarta.persistence.OneToMany;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.mock.web.MockMultipartFile;

@ExtendWith(MockitoExtension.class)
class AdminPartnersServiceTest {

    @Mock
    private PartnerRepository partnerRepository;

    @Mock
    private PartnerMetricRepository partnerMetricRepository;

    @Mock
    private PartnerTokenRepository partnerTokenRepository;

    @Mock
    private CommunityRepository communityRepository;

    @Mock
    private CommunityDiscordRepository communityDiscordRepository;

    @Mock
    private EventRepository eventRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PartnerSlugService partnerSlugService;

    @Mock
    private UserDiscordRepository userDiscordRepository;

    @Mock
    private UserTelegramRepository userTelegramRepository;

    @Mock
    private PartnerImageStorageService partnerImageStorageService;

    @Mock
    private PartnerImageTransactionCleanup partnerImageTransactionCleanup;

    @InjectMocks
    private AdminPartnersService service;

    @Test
    void getReturnsCompletePartnerWithLinksResourceAndAggregatedMetrics() {
        Partner partner = detailedPartner();
        PartnerMetricTotalsProjection totals = org.mockito.Mockito.mock(PartnerMetricTotalsProjection.class);
        when(totals.getPartnerId()).thenReturn(7);
        when(totals.getClicks()).thenReturn(123L);
        when(totals.getInvites()).thenReturn(45L);
        when(partnerRepository.findWithDetailsById(7)).thenReturn(Optional.of(partner));
        when(partnerMetricRepository.sumTotalsByPartnerIds(List.of(7))).thenReturn(List.of(totals));

        AdminPartnerItem item = service.get(7);

        assertEquals(7, item.id());
        assertEquals("Parceiro", item.name());
        assertEquals("Representante", item.representativeName());
        assertEquals(11, item.representativeUserId());
        assertEquals("active", item.status());
        assertEquals("community", item.category());
        assertEquals("Descricao", item.description());
        assertEquals("https://cdn.example/image.png", item.imageUrl());
        assertEquals("https://example.com", item.websiteUrl());
        assertEquals("Contato", item.contactName());
        assertEquals("contato@example.com", item.contactEmail());
        assertEquals(new PartnerLinkedResource("event", 20, null, "Evento"), item.linkedResource());
        assertEquals(List.of(new PartnerExternalLinkItem("instagram", "Instagram", "https://instagram.com/example")), item.links());
        assertEquals(123L, item.clicks());
        assertEquals(45L, item.invites());
        assertEquals("2026-01-02T03:04", item.createdAt());
    }

    @Test
    void getMissingPartnerReturnsNotFound() {
        when(partnerRepository.findWithDetailsById(99)).thenReturn(Optional.empty());

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> service.get(99));

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        assertEquals("Parceria nao encontrada", error.getReason());
        verify(partnerMetricRepository, never()).sumTotalsByPartnerIds(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"community", "creator", "artist", "event", "meet", "discord_server", "brand", "other"})
    void createAcceptsOfficialCategories(String category) {
        when(partnerRepository.save(any(Partner.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AdminPartnerItem item = service.create(createRequest(category));

        assertEquals(category, item.category());
    }

    @ParameterizedTest
    @ValueSource(strings = {"community", "creator", "artist", "event", "meet", "discord_server", "brand", "other"})
    void updateAcceptsOfficialCategories(String category) {
        Partner partner = basePartner();
        when(partnerRepository.findWithDetailsById(7)).thenReturn(Optional.of(partner));
        when(partnerRepository.save(partner)).thenReturn(partner);

        AdminPartnerItem item = service.update(7, updateRequest(category));

        assertEquals(category, item.category());
    }

    @Test
    void unknownCategoryIsRejectedForCreateAndUpdate() {
        ResponseStatusException createError = assertThrows(
            ResponseStatusException.class,
            () -> service.create(createRequest("unknown"))
        );
        Partner partner = basePartner();
        when(partnerRepository.findWithDetailsById(7)).thenReturn(Optional.of(partner));
        ResponseStatusException updateError = assertThrows(
            ResponseStatusException.class,
            () -> service.update(7, updateRequest("unknown"))
        );

        assertEquals(HttpStatus.BAD_REQUEST, createError.getStatusCode());
        assertEquals(HttpStatus.BAD_REQUEST, updateError.getStatusCode());
    }

    @Test
    void updateWithOmittedFieldsPreservesExistingValuesAndRelations() {
        Partner partner = detailedPartner();
        PartnerLink linkedResource = partner.getLinks().iterator().next();
        PartnerExternalLink externalLink = partner.getExternalLinks().iterator().next();
        User representative = partner.getRepresentativeUser();
        when(partnerRepository.findWithDetailsById(7)).thenReturn(Optional.of(partner));
        when(partnerRepository.save(partner)).thenReturn(partner);

        service.update(7, new UpdatePartnerRequest(
            null, null, null, null, null, null, null, null, null,
            null, null, null, null, null, null
        ));

        assertEquals("Parceiro", partner.getName());
        assertEquals("Descricao", partner.getDescription());
        assertEquals(representative, partner.getRepresentativeUser());
        assertTrue(partner.getLinks().contains(linkedResource));
        assertTrue(partner.getExternalLinks().contains(externalLink));
    }

    @Test
    void artistCandidatesUseUsersAndReturnSafePrefill() {
        User user = new User();
        user.setId(12);
        user.setDisplayName("Artista");
        user.setEmail("artist@example.com");
        user.setProfileImageUrl("https://cdn.example/artist.png");
        when(userRepository.searchAdminUsers(any(), any())).thenReturn(new PageImpl<>(List.of(user)));

        List<PartnerLinkCandidate> candidates = service.listLinkableResources("artist", "art", 20);

        assertEquals(1, candidates.size());
        assertEquals("user", candidates.getFirst().resourceType());
        assertEquals(12, candidates.getFirst().id());
        assertEquals("artist@example.com", candidates.getFirst().prefill().contactEmail());
    }

    @Test
    void discordInternalLinkUsesInternalIdAndNormalizesGuildId() {
        CommunityDiscord discord = new CommunityDiscord();
        discord.setId(5);
        discord.setGuildId(123456789012345678L);
        when(communityDiscordRepository.findById(5)).thenReturn(Optional.of(discord));
        when(partnerRepository.save(any(Partner.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AdminPartnerItem item = service.create(new CreatePartnerRequest(
            "Servidor", "servidor", "discord_server", "active", null, null, null, null, null,
            null, null, null, new PartnerLinkedResource("discord_guild", 5, "999", "Servidor"), null
        ));

        assertEquals(5, item.linkedResource().id());
        assertEquals("123456789012345678", item.linkedResource().externalRef());
    }

    @Test
    void discordExternalLinkRejectsInvalidSnowflake() {
        assertThrows(ResponseStatusException.class, () -> service.create(new CreatePartnerRequest(
            "Servidor", "servidor", "discord_server", "active", null, null, null, null, null,
            null, null, null, new PartnerLinkedResource("discord_guild", null, "abc", "Servidor"), null
        )));
    }

    @Test
    void clearLinkedResourceRemovesExistingLink() {
        Partner partner = basePartner();
        partner.setCategory("event");
        PartnerLink link = eventLink(partner, 20);
        partner.getLinks().add(link);
        when(partnerRepository.findWithDetailsById(7)).thenReturn(Optional.of(partner));
        when(partnerRepository.save(partner)).thenReturn(partner);

        service.update(7, new UpdatePartnerRequest(
            null, null, null, null, null, null, null, null, null,
            null, null, null, null, true, null
        ));

        assertTrue(partner.getLinks().isEmpty());
        verify(eventRepository, never()).save(any());
    }

    @Test
    void categoryChangeWithIncompatibleExistingLinkIsRejected() {
        Partner partner = basePartner();
        partner.setCategory("event");
        partner.getLinks().add(eventLink(partner, 20));
        when(partnerRepository.findWithDetailsById(7)).thenReturn(Optional.of(partner));

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> service.update(
            7, updateRequest("artist")
        ));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
    }

    @Test
    void creatingActiveEventPartnerDoesNotChangePartnerEvent() {
        Event event = new Event();
        event.setId(20);
        event.setIsEvent(true);
        when(eventRepository.findByIdForUpdate(20)).thenReturn(Optional.of(event));
        when(partnerRepository.save(any(Partner.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service.create(new CreatePartnerRequest(
            "Evento", "evento", "event", "active", null, null, null, null, null,
            null, null, null, new PartnerLinkedResource("event", 20, null, "Evento"), null
        ));

        verify(eventRepository).findByIdForUpdate(20);
        verify(eventRepository, never()).save(any());
    }

    @Test
    void eventAndMeetLinksValidateEventKind() {
        Event event = new Event();
        event.setId(20);
        event.setIsEvent(true);
        Event meet = new Event();
        meet.setId(21);
        meet.setIsEvent(false);
        when(eventRepository.findByIdForUpdate(20)).thenReturn(Optional.of(event));
        when(eventRepository.findByIdForUpdate(21)).thenReturn(Optional.of(meet));
        when(partnerRepository.save(any(Partner.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertNotNull(service.create(linkedCreate("event", new PartnerLinkedResource("event", 20, null, "Evento"))));
        assertNotNull(service.create(linkedCreate("meet", new PartnerLinkedResource("event", 21, null, "Meet"))));
        assertThrows(ResponseStatusException.class, () -> service.create(
            linkedCreate("event", new PartnerLinkedResource("event", 21, null, "Meet"))
        ));
        assertThrows(ResponseStatusException.class, () -> service.create(
            linkedCreate("meet", new PartnerLinkedResource("event", 20, null, "Evento"))
        ));
    }

    @Test
    void creatorAndArtistAcceptExistingUsers() {
        when(userRepository.existsById(12)).thenReturn(true);
        when(partnerRepository.save(any(Partner.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertEquals("user", service.create(linkedCreate(
            "creator", new PartnerLinkedResource("user", 12, null, "Criador")
        )).linkedResource().type());
        assertEquals("user", service.create(linkedCreate(
            "artist", new PartnerLinkedResource("user", 12, null, "Artista")
        )).linkedResource().type());
    }

    @Test
    void ambiguousClearAndReplacementIsRejected() {
        Partner partner = basePartner();
        when(partnerRepository.findWithDetailsById(7)).thenReturn(Optional.of(partner));

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> service.update(7,
            new UpdatePartnerRequest(null, null, null, null, null, null, null, null, null,
                null, null, null, new PartnerLinkedResource("user", 12, null, null), true, null)));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
    }

    @ParameterizedTest
    @ValueSource(strings = {"event", "artist", "brand", "other"})
    void externalLinkRemainsCompatibleWithEveryCategory(String category) {
        when(partnerRepository.save(any(Partner.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AdminPartnerItem item = service.create(linkedCreate(
            category, new PartnerLinkedResource("external", null, "legacy-ref", "Externo")
        ));

        assertEquals("external", item.linkedResource().type());
    }

    @Test
    void telegramGroupRemainsCompatibleAndRequiresAReference() {
        when(partnerRepository.save(any(Partner.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertEquals("telegram_group", service.create(linkedCreate(
            "artist", new PartnerLinkedResource("telegram_group", null, "telegram-ref", "Telegram")
        )).linkedResource().type());
        assertThrows(ResponseStatusException.class, () -> service.create(linkedCreate(
            "event", new PartnerLinkedResource("telegram_group", null, null, "Telegram")
        )));
    }

    @Test
    void clearingCommunityLinkAlsoClearsLegacyCommunity() {
        Community community = community(7, "Comunidade A");
        Partner partner = basePartner();
        partner.setCategory("community");
        partner.setCommunity(community);
        PartnerLink link = new PartnerLink();
        link.setPartner(partner);
        link.setTargetType("community");
        link.setTargetId(7);
        partner.getLinks().add(link);
        when(partnerRepository.findWithDetailsById(7)).thenReturn(Optional.of(partner));
        when(partnerRepository.save(partner)).thenReturn(partner);

        service.update(7, new UpdatePartnerRequest(null, null, null, null, null, null, null, null, null,
            null, null, null, null, true, null));

        assertTrue(partner.getLinks().isEmpty());
        assertEquals(null, partner.getCommunity());
    }

    @Test
    void replacingCommunityWithUserClearsLegacyCommunity() {
        Community community = community(7, "Comunidade A");
        Partner partner = basePartner();
        partner.setCategory("community");
        partner.setCommunity(community);
        PartnerLink oldLink = new PartnerLink();
        oldLink.setPartner(partner);
        oldLink.setTargetType("community");
        oldLink.setTargetId(7);
        partner.getLinks().add(oldLink);
        when(partnerRepository.findWithDetailsById(7)).thenReturn(Optional.of(partner));
        when(partnerRepository.save(partner)).thenReturn(partner);
        when(userRepository.existsById(42)).thenReturn(true);

        service.update(7, new UpdatePartnerRequest(null, null, "artist", null, null, null, null, null, null,
            null, null, null, new PartnerLinkedResource("user", 42, null, "Artista"), false, null));

        assertEquals(null, partner.getCommunity());
        assertEquals("user", partner.getLinks().iterator().next().getTargetType());
    }

    @Test
    void replacingCommunityWithEventClearsLegacyCommunity() {
        Partner partner = basePartner();
        partner.setCategory("community");
        partner.setCommunity(community(7, "Comunidade A"));
        PartnerLink oldLink = new PartnerLink();
        oldLink.setPartner(partner);
        oldLink.setTargetType("community");
        oldLink.setTargetId(7);
        partner.getLinks().add(oldLink);
        Event event = new Event();
        event.setId(20);
        event.setIsEvent(true);
        when(partnerRepository.findWithDetailsById(7)).thenReturn(Optional.of(partner));
        when(partnerRepository.save(partner)).thenReturn(partner);
        when(eventRepository.findByIdForUpdate(20)).thenReturn(Optional.of(event));

        service.update(7, new UpdatePartnerRequest(null, null, "event", null, null, null, null, null, null,
            null, null, null, new PartnerLinkedResource("event", 20, null, "Evento"), false, null));

        assertEquals(null, partner.getCommunity());
        verify(eventRepository, never()).save(any());
    }

    @Test
    void replacingCommunityLinkSynchronizesLegacyCommunity() {
        Community oldCommunity = community(7, "Comunidade A");
        Community newCommunity = community(8, "Comunidade B");
        Partner partner = basePartner();
        partner.setCategory("community");
        partner.setCommunity(oldCommunity);
        when(partnerRepository.findWithDetailsById(7)).thenReturn(Optional.of(partner));
        when(partnerRepository.save(partner)).thenReturn(partner);
        when(communityRepository.existsById(8)).thenReturn(true);
        when(communityRepository.findById(8)).thenReturn(Optional.of(newCommunity));

        service.update(7, new UpdatePartnerRequest(null, null, null, null, null, null, null, null, null,
            null, null, null, new PartnerLinkedResource("community", 8, null, "Comunidade B"), false, null));

        assertEquals(newCommunity, partner.getCommunity());
    }

    @Test
    void divergentCommunityIdAndLinkedResourceAreRejected() {
        Partner partner = basePartner();
        when(partnerRepository.findWithDetailsById(7)).thenReturn(Optional.of(partner));
        when(communityRepository.findById(7)).thenReturn(Optional.of(community(7, "Comunidade A")));

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> service.update(7,
            new UpdatePartnerRequest(null, null, "community", null, null, null, null, null, null,
                null, 7, null, new PartnerLinkedResource("community", 8, null, "Comunidade B"), false, null)));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
    }

    @Test
    void statusChangesAndDeleteDoNotChangePartnerEvent() {
        Partner partner = basePartner();
        partner.setCategory("event");
        partner.getLinks().add(eventLink(partner, 20));
        when(partnerRepository.findWithDetailsById(7)).thenReturn(Optional.of(partner));
        when(partnerRepository.save(partner)).thenReturn(partner);

        service.updateStatus(7, new com.Brafurries.API.admin.dto.AdminDtos.UpdatePartnerStatusRequest("suspended"));
        service.updateStatus(7, new com.Brafurries.API.admin.dto.AdminDtos.UpdatePartnerStatusRequest("active"));
        service.delete(7);

        verify(eventRepository, never()).save(any());
    }

    @Test
    void deleteRemovesExclusiveDependenciesBeforePartner() {
        Partner partner = detailedPartner();
        when(partnerRepository.findWithDetailsById(7)).thenReturn(Optional.of(partner));

        service.delete(7);

        InOrder order = inOrder(partnerTokenRepository, partnerMetricRepository, partnerRepository);
        order.verify(partnerTokenRepository).deleteByPartnerId(7);
        order.verify(partnerMetricRepository).deleteByPartner_Id(7);
        order.verify(partnerRepository).delete(partner);
    }

    @Test
    void deleteMissingPartnerReturnsNotFoundWithoutDeletingDependencies() {
        when(partnerRepository.findWithDetailsById(99)).thenReturn(Optional.empty());

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> service.delete(99));

        assertEquals(HttpStatus.NOT_FOUND, error.getStatusCode());
        verify(partnerTokenRepository, never()).deleteByPartnerId(any());
        verify(partnerMetricRepository, never()).deleteByPartner_Id(any());
        verify(partnerRepository, never()).delete(any());
    }

    @Test
    void deleteIsTransactionalAndStillEvictsPartnerCaches() throws Exception {
        Method method = AdminPartnersService.class.getMethod("delete", Integer.class);
        assertNotNull(method.getAnnotation(Transactional.class));
        Caching caching = method.getAnnotation(Caching.class);
        assertNotNull(caching);
        List<String> cacheNames = Arrays.stream(caching.evict())
            .flatMap(eviction -> Arrays.stream(eviction.cacheNames()))
            .toList();

        assertTrue(cacheNames.contains(CacheConfig.ADMIN_PARTNERS));
        assertTrue(cacheNames.contains(CacheConfig.ADMIN_OVERVIEW));
    }

    @Test
    void createAssociatesExistingRepresentativeAndRejectsUnknownUser() {
        User representative = new User();
        representative.setId(12);
        representative.setUsername("fox");
        when(userRepository.findById(12)).thenReturn(Optional.of(representative));
        when(partnerRepository.save(any(Partner.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AdminPartnerItem created = service.create(new CreatePartnerRequest(
            "Parceiro", null, "brand", null, null, null, null, null, null,
            12, null, null, null, null
        ));

        assertEquals(12, created.representativeUserId());
        when(userRepository.findById(99)).thenReturn(Optional.empty());
        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> service.create(new CreatePartnerRequest(
            "Parceiro 2", null, "brand", null, null, null, null, null, null,
            99, null, null, null, null
        )));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
    }

    @Test
    void clearRepresentativeRemovesItAndAmbiguousPayloadIsRejected() {
        Partner partner = detailedPartner();
        when(partnerRepository.findWithDetailsById(7)).thenReturn(Optional.of(partner));
        when(partnerRepository.save(partner)).thenReturn(partner);

        service.update(7, new UpdatePartnerRequest(null, null, null, null, null, null, null, null, null,
            null, true, null, null, null, null, null));
        assertEquals(null, partner.getRepresentativeUser());

        partner.setRepresentativeUser(detailedPartner().getRepresentativeUser());
        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> service.update(7,
            new UpdatePartnerRequest(null, null, null, null, null, null, null, null, null,
                12, true, null, null, null, null, null)));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
    }

    @Test
    void representativeSummaryUsesCurrentAccountsInBatch() {
        Partner partner = detailedPartner();
        User representative = partner.getRepresentativeUser();
        representative.setEmail("rep@example.com");
        representative.setProfileImageUrl("https://cdn.example/rep.webp");
        UserDiscord discord = new UserDiscord();
        discord.setUser(representative);
        discord.setUsername("discord-fox");
        UserTelegram telegram = new UserTelegram();
        telegram.setUser(representative);
        telegram.setUsername("telegram-fox");
        when(partnerRepository.findAdminPartners(null, null)).thenReturn(List.of(partner, partner));
        when(userDiscordRepository.findByUserIdIn(List.of(11))).thenReturn(List.of(discord));
        when(userTelegramRepository.findByUserIdIn(List.of(11))).thenReturn(List.of(telegram));

        AdminPartnerItem item = service.listPartners(null, null).items().getFirst();

        assertEquals("Representante", item.representative().name());
        assertEquals("discord-fox", item.representative().discordUsername());
        assertEquals("telegram-fox", item.representative().telegramUsername());
        verify(userDiscordRepository).findByUserIdIn(List.of(11));
        verify(userTelegramRepository).findByUserIdIn(List.of(11));
    }

    @Test
    void uploadAndDeleteImageUpdatePartnerAndOnlyRequestManagedCleanup() {
        Partner partner = basePartner();
        partner.setImageUrl("https://cdn.example/partners/7/image/old.webp");
        MockMultipartFile file = new MockMultipartFile("file", "logo.png", "image/png", new byte[] {1});
        when(partnerRepository.findWithDetailsById(7)).thenReturn(Optional.of(partner));
        when(partnerRepository.save(partner)).thenReturn(partner);
        when(partnerImageStorageService.uploadPartnerImage(7, file)).thenReturn(
            new PartnerImageStorageService.StoredPartnerImage("partners/7/image/new.webp", "https://cdn.example/partners/7/image/new.webp", "image/webp")
        );

        assertEquals("https://cdn.example/partners/7/image/new.webp", service.uploadImage(7, file).imageUrl());
        verify(partnerImageTransactionCleanup).deleteUploadedOnRollback(7, "partners/7/image/new.webp");
        verify(partnerImageTransactionCleanup).deletePreviousAfterCommit(7, "https://cdn.example/partners/7/image/old.webp");
        assertEquals(null, service.deleteImage(7).imageUrl());
        verify(partnerImageTransactionCleanup).deletePreviousAfterCommit(7, "https://cdn.example/partners/7/image/new.webp");
    }

    @Test
    void deletePartnerCleansManagedImageWithoutBlockingDeletion() {
        Partner partner = basePartner();
        partner.setImageUrl("https://cdn.example/partners/7/image/logo.webp");
        when(partnerRepository.findWithDetailsById(7)).thenReturn(Optional.of(partner));
        service.delete(7);
        verify(partnerImageTransactionCleanup).deletePreviousAfterCommit(7, partner.getImageUrl());
    }

    @Test
    void partnerOwnedLinkCollectionsKeepExplicitCascadeAndOrphanRemoval() throws Exception {
        assertOwnedCollectionMapping(Partner.class.getDeclaredField("links"));
        assertOwnedCollectionMapping(Partner.class.getDeclaredField("externalLinks"));
    }

    private void assertOwnedCollectionMapping(Field field) {
        OneToMany mapping = field.getAnnotation(OneToMany.class);
        assertNotNull(mapping);
        assertTrue(mapping.orphanRemoval());
        assertTrue(Arrays.asList(mapping.cascade()).contains(CascadeType.ALL));
    }

    private CreatePartnerRequest createRequest(String category) {
        return new CreatePartnerRequest(
            "Parceiro", "parceiro", category, "active", null, null, null, null, null,
            null, null, null, null, null
        );
    }

    private UpdatePartnerRequest updateRequest(String category) {
        return new UpdatePartnerRequest(
            null, null, category, null, null, null, null, null, null,
            null, null, null, null, null, null
        );
    }

    private CreatePartnerRequest linkedCreate(String category, PartnerLinkedResource resource) {
        return new CreatePartnerRequest(
            "Parceiro", "parceiro", category, "active", null, null, null, null, null,
            null, null, null, resource, null
        );
    }

    private Partner basePartner() {
        Partner partner = new Partner();
        partner.setId(7);
        partner.setName("Parceiro");
        partner.setSlug("parceiro");
        partner.setCategory("community");
        partner.setStatus("active");
        partner.setCreatedAt(LocalDateTime.of(2026, 1, 2, 3, 4));
        return partner;
    }

    private Partner detailedPartner() {
        Partner partner = basePartner();
        partner.setDescription("Descricao");
        partner.setImageUrl("https://cdn.example/image.png");
        partner.setWebsiteUrl("https://example.com");
        partner.setContactName("Contato");
        partner.setContactEmail("contato@example.com");
        partner.setSecret("must-not-be-exposed");

        User representative = new User();
        representative.setId(11);
        representative.setDisplayName("Representante");
        representative.setPasswordHash("must-not-be-exposed");
        partner.setRepresentativeUser(representative);

        PartnerLink linkedResource = new PartnerLink();
        linkedResource.setId(30);
        linkedResource.setPartner(partner);
        linkedResource.setTargetType("event");
        linkedResource.setTargetId(20);
        linkedResource.setLabel("Evento");
        partner.getLinks().add(linkedResource);

        PartnerExternalLink externalLink = new PartnerExternalLink();
        externalLink.setId(40);
        externalLink.setPartner(partner);
        externalLink.setType("instagram");
        externalLink.setLabel("Instagram");
        externalLink.setUrl("https://instagram.com/example");
        partner.getExternalLinks().add(externalLink);
        return partner;
    }

    private PartnerLink eventLink(Partner partner, Integer eventId) {
        PartnerLink link = new PartnerLink();
        link.setPartner(partner);
        link.setTargetType("event");
        link.setTargetId(eventId);
        return link;
    }

    private Community community(Integer id, String name) {
        Community community = new Community();
        community.setId(id);
        community.setName(name);
        return community;
    }
}
