package com.Brafurries.API.admin;

import static com.Brafurries.API.config.CacheConfig.ADMIN_OVERVIEW;
import static com.Brafurries.API.config.CacheConfig.ADMIN_PARTNERS;
import static com.Brafurries.API.config.CacheConfig.DASHBOARD_EVENT_CARDS;
import static com.Brafurries.API.config.CacheConfig.DASHBOARD_MANAGED_EVENT;

import com.Brafurries.API.admin.dto.AdminDtos.AdminPartnerItem;
import com.Brafurries.API.admin.dto.AdminDtos.AdminPartnersResponse;
import com.Brafurries.API.admin.dto.AdminDtos.CreatePartnerRequest;
import com.Brafurries.API.admin.dto.AdminDtos.PartnerExternalLinkItem;
import com.Brafurries.API.admin.dto.AdminDtos.PartnerImageResponse;
import com.Brafurries.API.admin.dto.AdminDtos.PartnerLinkCandidate;
import com.Brafurries.API.admin.dto.AdminDtos.PartnerLinkedResource;
import com.Brafurries.API.admin.dto.AdminDtos.PartnerPrefill;
import com.Brafurries.API.admin.dto.AdminDtos.PartnerRepresentative;
import com.Brafurries.API.admin.dto.AdminDtos.UpdatePartnerRequest;
import com.Brafurries.API.admin.dto.AdminDtos.UpdatePartnerStatusRequest;
import com.Brafurries.API.entity.misc.Partner;
import com.Brafurries.API.entity.misc.PartnerExternalLink;
import com.Brafurries.API.entity.misc.PartnerLink;
import com.Brafurries.API.entity.community.Community;
import com.Brafurries.API.entity.community.CommunityDiscord;
import com.Brafurries.API.entity.event.Event;
import com.Brafurries.API.entity.user.User;
import com.Brafurries.API.entity.user.UserDiscord;
import com.Brafurries.API.entity.user.UserTelegram;
import com.Brafurries.API.partner.PartnerImageStorageService;
import com.Brafurries.API.partner.PartnerImageTransactionCleanup;
import com.Brafurries.API.partner.PartnerSlugService;
import com.Brafurries.API.repository.community.CommunityDiscordRepository;
import com.Brafurries.API.repository.community.CommunityRepository;
import com.Brafurries.API.repository.event.EventRepository;
import com.Brafurries.API.repository.misc.PartnerMetricRepository;
import com.Brafurries.API.repository.misc.PartnerMetricRepository.PartnerMetricTotalsProjection;
import com.Brafurries.API.repository.misc.PartnerRepository;
import com.Brafurries.API.repository.misc.PartnerTokenRepository;
import com.Brafurries.API.repository.user.UserRepository;
import com.Brafurries.API.repository.user.UserDiscordRepository;
import com.Brafurries.API.repository.user.UserTelegramRepository;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.PageRequest;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AdminPartnersService {

    private static final Set<String> CATEGORIES = Set.of(
        "community", "creator", "artist", "event", "meet", "discord_server", "brand", "other"
    );
    private static final Set<String> STATUSES = Set.of("pending", "active", "suspended");
    private static final Set<String> TARGET_TYPES = Set.of(
        "community", "event", "user", "discord_guild", "telegram_group", "external"
    );
    private static final java.util.regex.Pattern DISCORD_SNOWFLAKE = java.util.regex.Pattern.compile("[1-9][0-9]*");

    private final PartnerRepository partnerRepository;
    private final PartnerMetricRepository partnerMetricRepository;
    private final PartnerTokenRepository partnerTokenRepository;
    private final CommunityRepository communityRepository;
    private final CommunityDiscordRepository communityDiscordRepository;
    private final EventRepository eventRepository;
    private final UserRepository userRepository;
    private final PartnerSlugService partnerSlugService;
    private final UserDiscordRepository userDiscordRepository;
    private final UserTelegramRepository userTelegramRepository;
    private final PartnerImageStorageService partnerImageStorageService;
    private final PartnerImageTransactionCleanup partnerImageTransactionCleanup;

    public AdminPartnersService(
        PartnerRepository partnerRepository,
        PartnerMetricRepository partnerMetricRepository,
        PartnerTokenRepository partnerTokenRepository,
        CommunityRepository communityRepository,
        CommunityDiscordRepository communityDiscordRepository,
        EventRepository eventRepository,
        UserRepository userRepository,
        PartnerSlugService partnerSlugService,
        UserDiscordRepository userDiscordRepository,
        UserTelegramRepository userTelegramRepository,
        PartnerImageStorageService partnerImageStorageService,
        PartnerImageTransactionCleanup partnerImageTransactionCleanup
    ) {
        this.partnerRepository = partnerRepository;
        this.partnerMetricRepository = partnerMetricRepository;
        this.partnerTokenRepository = partnerTokenRepository;
        this.communityRepository = communityRepository;
        this.communityDiscordRepository = communityDiscordRepository;
        this.eventRepository = eventRepository;
        this.userRepository = userRepository;
        this.partnerSlugService = partnerSlugService;
        this.userDiscordRepository = userDiscordRepository;
        this.userTelegramRepository = userTelegramRepository;
        this.partnerImageStorageService = partnerImageStorageService;
        this.partnerImageTransactionCleanup = partnerImageTransactionCleanup;
    }

    @Transactional(readOnly = true)
    @Cacheable(
        cacheNames = ADMIN_PARTNERS,
        key = "(#status == null ? '' : #status.trim().toLowerCase()) + ':' + (#category == null ? '' : #category.trim().toLowerCase())"
    )
    public AdminPartnersResponse listPartners(String status, String category) {
        String normalizedStatus = blankToNull(status);
        String normalizedCategory = blankToNull(category);
        if (normalizedStatus != null) {
            normalizedStatus = normalizeAndValidate(normalizedStatus, STATUSES, "Status invalido");
        }
        if (normalizedCategory != null) {
            normalizedCategory = normalizeAndValidate(normalizedCategory, CATEGORIES, "Categoria invalida");
        }
        List<Partner> partners = partnerRepository.findAdminPartners(normalizedStatus, normalizedCategory);
        Map<Integer, PartnerMetricTotalsProjection> metricsByPartner = metricsByPartner(partners);
        Map<Integer, PartnerRepresentative> representativesByUser = representativesByUser(partners);

        List<AdminPartnerItem> items = partners.stream()
            .map(partner -> toItem(partner, metricsByPartner.get(partner.getId()), representativesByUser))
            .toList();
        return new AdminPartnersResponse(items);
    }

    @Transactional(readOnly = true)
    public AdminPartnerItem get(Integer id) {
        Partner partner = findPartner(id);
        PartnerMetricTotalsProjection metrics = metricsByPartner(List.of(partner)).get(partner.getId());
        return toItem(partner, metrics, representativesByUser(List.of(partner)));
    }

    @Transactional(readOnly = true)
    public List<PartnerLinkCandidate> listLinkableResources(String category, String search, Integer limit) {
        String normalizedCategory = normalizeAndValidate(category, CATEGORIES, "Categoria invalida");
        int pageSize = Math.min(Math.max(limit == null ? 20 : limit, 1), 50);
        String normalizedSearch = trimToNull(search);
        PageRequest page = PageRequest.of(0, pageSize);
        return switch (normalizedCategory) {
            case "event", "meet" -> eventRepository
                .searchPartnerCandidates("event".equals(normalizedCategory), normalizedSearch, page)
                .stream().map(this::eventCandidate).toList();
            case "creator", "artist" -> userRepository.searchAdminUsers(normalizedSearch, page)
                .stream().map(this::userCandidate).toList();
            case "community" -> communityRepository
                .findByNameContainingIgnoreCase(defaultValue(normalizedSearch, ""), page)
                .stream().map(this::communityCandidate).toList();
            case "discord_server" -> communityDiscordRepository
                .findByNameContainingIgnoreCase(defaultValue(normalizedSearch, ""), page)
                .stream().map(this::discordCandidate).toList();
            case "brand", "other" -> List.of();
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Categoria invalida");
        };
    }

    @Transactional
    @Caching(evict = {
        @CacheEvict(cacheNames = ADMIN_PARTNERS, allEntries = true),
        @CacheEvict(cacheNames = ADMIN_OVERVIEW, allEntries = true),
        @CacheEvict(cacheNames = DASHBOARD_EVENT_CARDS, allEntries = true),
        @CacheEvict(cacheNames = DASHBOARD_MANAGED_EVENT, allEntries = true)
    })
    public AdminPartnerItem create(CreatePartnerRequest request) {
        Partner partner = new Partner();
        partner.setName(required(request.name(), "Nome obrigatorio"));
        applyCreateFields(partner, request);
        Partner saved = partnerRepository.save(partner);
        return toItem(saved, null, representativesByUser(List.of(saved)));
    }

    @Transactional
    @Caching(evict = {
        @CacheEvict(cacheNames = ADMIN_PARTNERS, allEntries = true),
        @CacheEvict(cacheNames = ADMIN_OVERVIEW, allEntries = true),
        @CacheEvict(cacheNames = DASHBOARD_EVENT_CARDS, allEntries = true),
        @CacheEvict(cacheNames = DASHBOARD_MANAGED_EVENT, allEntries = true)
    })
    public AdminPartnerItem update(Integer id, UpdatePartnerRequest request) {
        Partner partner = findPartner(id);
        if (request.name() != null) {
            partner.setName(required(request.name(), "Nome obrigatorio"));
        }
        applyUpdateFields(partner, request);
        Partner saved = partnerRepository.save(partner);
        return toItem(saved, null, representativesByUser(List.of(saved)));
    }

    @Transactional
    @Caching(evict = {
        @CacheEvict(cacheNames = ADMIN_PARTNERS, allEntries = true),
        @CacheEvict(cacheNames = ADMIN_OVERVIEW, allEntries = true),
        @CacheEvict(cacheNames = DASHBOARD_EVENT_CARDS, allEntries = true),
        @CacheEvict(cacheNames = DASHBOARD_MANAGED_EVENT, allEntries = true)
    })
    public AdminPartnerItem updateStatus(Integer id, UpdatePartnerStatusRequest request) {
        String status = normalizeAndValidate(request.status(), STATUSES, "Status invalido");
        Partner partner = findPartner(id);
        partner.setStatus(status);
        Partner saved = partnerRepository.save(partner);
        return toItem(saved, null, representativesByUser(List.of(saved)));
    }

    @Transactional
    @Caching(evict = {
        @CacheEvict(cacheNames = ADMIN_PARTNERS, allEntries = true),
        @CacheEvict(cacheNames = ADMIN_OVERVIEW, allEntries = true),
        @CacheEvict(cacheNames = DASHBOARD_EVENT_CARDS, allEntries = true),
        @CacheEvict(cacheNames = DASHBOARD_MANAGED_EVENT, allEntries = true)
    })
    public void delete(Integer id) {
        Partner partner = findPartner(id);
        String previousImageUrl = partner.getImageUrl();
        partnerTokenRepository.deleteByPartnerId(id);
        partnerMetricRepository.deleteByPartner_Id(id);
        partnerRepository.delete(partner);
        partnerImageTransactionCleanup.deletePreviousAfterCommit(id, previousImageUrl);
    }

    @Transactional
    @Caching(evict = {
        @CacheEvict(cacheNames = ADMIN_PARTNERS, allEntries = true),
        @CacheEvict(cacheNames = ADMIN_OVERVIEW, allEntries = true)
    })
    public PartnerImageResponse uploadImage(Integer id, MultipartFile file) {
        Partner partner = findPartner(id);
        String previousImageUrl = partner.getImageUrl();
        var stored = partnerImageStorageService.uploadPartnerImage(partner.getId(), file);
        partnerImageTransactionCleanup.deleteUploadedOnRollback(partner.getId(), stored.key());
        partner.setImageUrl(stored.url());
        Partner saved = partnerRepository.save(partner);
        if (!stored.url().equals(previousImageUrl)) {
            partnerImageTransactionCleanup.deletePreviousAfterCommit(partner.getId(), previousImageUrl);
        }
        return new PartnerImageResponse(saved.getId(), saved.getImageUrl());
    }

    @Transactional
    @Caching(evict = {
        @CacheEvict(cacheNames = ADMIN_PARTNERS, allEntries = true),
        @CacheEvict(cacheNames = ADMIN_OVERVIEW, allEntries = true)
    })
    public PartnerImageResponse deleteImage(Integer id) {
        Partner partner = findPartner(id);
        String previousImageUrl = partner.getImageUrl();
        partner.setImageUrl(null);
        Partner saved = partnerRepository.save(partner);
        partnerImageTransactionCleanup.deletePreviousAfterCommit(partner.getId(), previousImageUrl);
        return new PartnerImageResponse(saved.getId(), saved.getImageUrl());
    }

    private void applyCreateFields(Partner partner, CreatePartnerRequest request) {
        partner.setCategory(normalizeAndValidate(request.category(), CATEGORIES, "Categoria invalida"));
        partner.setStatus(normalizeAndValidate(defaultValue(request.status(), "pending"), STATUSES, "Status invalido"));
        partner.setSlug(uniqueSlug(defaultValue(request.slug(), request.name()), null));
        partner.setDescription(trimToNull(request.description()));
        partner.setImageUrl(trimToNull(request.imageUrl()));
        partner.setWebsiteUrl(trimToNull(request.websiteUrl()));
        partner.setContactName(trimToNull(request.contactName()));
        partner.setContactEmail(trimToNull(request.contactEmail()));
        setRepresentativeUser(partner, request.representativeUserId());
        setLegacyCommunity(partner, request.communityId());
        replaceLinkedResource(partner, compatibleLinkedResource(request.linkedResource(), request.communityId(), request.representativeName()), partner.getCategory());
        replaceExternalLinks(partner, request.links());
    }

    private void applyUpdateFields(Partner partner, UpdatePartnerRequest request) {
        if (Boolean.TRUE.equals(request.clearLinkedResource()) && request.linkedResource() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "clearLinkedResource e linkedResource nao podem ser enviados juntos");
        }
        if (Boolean.TRUE.equals(request.clearRepresentativeUser()) && request.representativeUserId() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "clearRepresentativeUser e representativeUserId nao podem ser enviados juntos");
        }
        if (request.category() != null) {
            partner.setCategory(normalizeAndValidate(request.category(), CATEGORIES, "Categoria invalida"));
        }
        if (request.status() != null) {
            partner.setStatus(normalizeAndValidate(request.status(), STATUSES, "Status invalido"));
        }
        if (request.slug() != null) {
            partner.setSlug(uniqueSlug(request.slug(), partner.getId()));
        }
        if (request.description() != null) {
            partner.setDescription(trimToNull(request.description()));
        }
        if (request.imageUrl() != null) {
            partner.setImageUrl(trimToNull(request.imageUrl()));
        }
        if (request.websiteUrl() != null) {
            partner.setWebsiteUrl(trimToNull(request.websiteUrl()));
        }
        if (request.contactName() != null) {
            partner.setContactName(trimToNull(request.contactName()));
        }
        if (request.contactEmail() != null) {
            partner.setContactEmail(trimToNull(request.contactEmail()));
        }
        if (Boolean.TRUE.equals(request.clearRepresentativeUser())) {
            partner.setRepresentativeUser(null);
        } else if (request.representativeUserId() != null) {
            setRepresentativeUser(partner, request.representativeUserId());
        }
        if (request.communityId() != null) {
            setLegacyCommunity(partner, request.communityId());
        }
        if (Boolean.TRUE.equals(request.clearLinkedResource())) {
            partner.getLinks().clear();
            partner.setCommunity(null);
        } else if (request.linkedResource() != null || request.communityId() != null) {
            replaceLinkedResource(partner, compatibleLinkedResource(request.linkedResource(), request.communityId(), request.representativeName()), partner.getCategory());
        }
        if (request.category() != null || request.linkedResource() != null || request.communityId() != null) {
            validateExistingLinkedResource(partner);
        }
        if (request.links() != null) {
            replaceExternalLinks(partner, request.links());
        }
    }

    private void replaceLinkedResource(Partner partner, PartnerLinkedResource resource, String category) {
        partner.getLinks().clear();
        if (resource == null || isBlank(resource.type())) {
            return;
        }

        String type = normalizeAndValidate(resource.type(), TARGET_TYPES, "Tipo de vinculo invalido");
        PartnerLinkedResource normalized = validateAndNormalizeTarget(category, type, resource);

        PartnerLink link = new PartnerLink();
        link.setPartner(partner);
        link.setTargetType(type);
        link.setTargetId(normalized.id());
        link.setExternalRef(normalized.externalRef());
        link.setLabel(trimToNull(normalized.label()));
        partner.getLinks().add(link);

        if ("community".equals(type) && normalized.id() != null) {
            setLegacyCommunity(partner, normalized.id());
        } else {
            partner.setCommunity(null);
        }
    }

    private void replaceExternalLinks(Partner partner, List<PartnerExternalLinkItem> links) {
        partner.getExternalLinks().clear();
        if (links == null) {
            return;
        }
        for (PartnerExternalLinkItem item : links) {
            if (item == null || isBlank(item.url())) {
                continue;
            }
            PartnerExternalLink link = new PartnerExternalLink();
            link.setPartner(partner);
            link.setType(defaultValue(item.type(), "website").trim().toLowerCase(Locale.ROOT));
            link.setLabel(trimToNull(item.label()));
            link.setUrl(item.url().trim());
            partner.getExternalLinks().add(link);
        }
    }

    private PartnerLinkedResource validateAndNormalizeTarget(String category, String type, PartnerLinkedResource resource) {
        validateCompatibility(category, type);
        Integer id = resource.id();
        String externalRef = trimToNull(resource.externalRef());
        switch (type) {
            case "community" -> {
                requireId(id, type);
                if (!communityRepository.existsById(id)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Comunidade vinculada nao encontrada");
                }
            }
            case "event" -> {
                requireId(id, type);
                Event event = eventRepository.findByIdForUpdate(id)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Evento vinculado nao encontrado"));
                boolean expectedEvent = "event".equals(category);
                if (!Boolean.valueOf(expectedEvent).equals(event.getIsEvent())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Evento vinculado incompativel com a categoria");
                }
            }
            case "user" -> {
                requireId(id, type);
                if (!userRepository.existsById(id)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Usuario vinculado nao encontrado");
                }
            }
            case "discord_guild" -> {
                if (id != null) {
                    CommunityDiscord discord = communityDiscordRepository.findById(id)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Servidor Discord vinculado nao encontrado"));
                    externalRef = String.valueOf(discord.getGuildId());
                } else if (externalRef == null || !DISCORD_SNOWFLAKE.matcher(externalRef).matches()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Guild ID deve ser uma string decimal positiva");
                }
            }
            case "telegram_group", "external" -> {
                if (externalRef == null && id == null) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Vinculo externo exige id ou externalRef");
                }
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tipo de vinculo invalido");
        }
        return new PartnerLinkedResource(type, id, externalRef, resource.label());
    }

    private void validateCompatibility(String category, String type) {
        if ("external".equals(type) || "telegram_group".equals(type)) {
            return;
        }
        boolean compatible = switch (category) {
            case "event", "meet" -> "event".equals(type);
            case "creator", "artist" -> "user".equals(type);
            case "discord_server" -> "discord_guild".equals(type);
            case "community" -> "community".equals(type);
            case "brand", "other" -> false;
            default -> false;
        };
        if (!compatible) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Vinculo incompativel com a categoria");
        }
    }

    private void validateExistingLinkedResource(Partner partner) {
        PartnerLink link = partner.getLinks().stream().findFirst().orElse(null);
        if (link == null) {
            return;
        }
        validateAndNormalizeTarget(partner.getCategory(), link.getTargetType(), new PartnerLinkedResource(
            link.getTargetType(), link.getTargetId(), link.getExternalRef(), link.getLabel()
        ));
    }

    private PartnerLinkCandidate eventCandidate(Event event) {
        List<PartnerExternalLinkItem> links = java.util.stream.Stream.of(
            suggestedLink("website", event.getWebsite()),
            suggestedLink("chat", event.getGroupChatLink()),
            suggestedLink("tickets", event.getTicketUrl())
        ).filter(java.util.Objects::nonNull).toList();
        String hostName = event.getHostUser() == null ? null : coalesce(
            event.getHostUser().getDisplayName(), event.getHostUser().getUsername()
        );
        String subtitle = (Boolean.TRUE.equals(event.getIsEvent()) ? "Evento" : "Meet")
            + (isBlank(event.getCity()) ? "" : " • " + event.getCity());
        return new PartnerLinkCandidate("event", event.getId(), null, event.getEventName(), subtitle,
            event.getEventLogoUrl(), new PartnerPrefill(event.getEventName(), event.getDescription(),
            event.getEventLogoUrl(), event.getWebsite(), hostName, null, links));
    }

    private PartnerLinkCandidate userCandidate(User user) {
        String name = coalesce(user.getDisplayName(), user.getUsername(), user.getEmail(), "Usuario " + user.getId());
        String contactName = coalesce(user.getDisplayName(), user.getUsername());
        return new PartnerLinkCandidate("user", user.getId(), null, name, user.getEmail(),
            user.getProfileImageUrl(), new PartnerPrefill(name, null, user.getProfileImageUrl(), null,
            contactName, user.getEmail(), List.of()));
    }

    private PartnerLinkCandidate communityCandidate(Community community) {
        return new PartnerLinkCandidate("community", community.getId(), null, community.getName(),
            "Comunidade", null, new PartnerPrefill(community.getName(), null, null, null, null, null, List.of()));
    }

    private PartnerLinkCandidate discordCandidate(CommunityDiscord discord) {
        String guildId = String.valueOf(discord.getGuildId());
        String subtitle = "Guild " + guildId + " • " + discord.getUsersQuantity() + " membros • "
            + (Boolean.TRUE.equals(discord.getActive()) ? "ativo" : "inativo");
        return new PartnerLinkCandidate("discord_guild", discord.getId(), guildId, discord.getName(), subtitle,
            null, new PartnerPrefill(discord.getName(), null, null, null, null, null, List.of()));
    }

    private PartnerExternalLinkItem suggestedLink(String type, String url) {
        return isBlank(url) ? null : new PartnerExternalLinkItem(type, null, url);
    }

    private void setRepresentativeUser(Partner partner, Integer userId) {
        if (userId == null) {
            partner.setRepresentativeUser(null);
            return;
        }
        partner.setRepresentativeUser(userRepository.findById(userId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Representante nao encontrado")));
    }

    private void setLegacyCommunity(Partner partner, Integer communityId) {
        if (communityId == null) {
            return;
        }
        partner.setCommunity(communityRepository.findById(communityId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Comunidade nao encontrada")));
    }

    private AdminPartnerItem toItem(Partner partner, PartnerMetricTotalsProjection metrics, Map<Integer, PartnerRepresentative> representativesByUser) {
        PartnerLink linkedResource = partner.getLinks().stream()
            .min(Comparator.comparing(PartnerLink::getId, Comparator.nullsLast(Integer::compareTo)))
            .orElse(null);
        PartnerRepresentative representative = partner.getRepresentativeUser() == null ? null
            : representativesByUser.get(partner.getRepresentativeUser().getId());
        String representativeName = representative != null
            ? representative.name()
            : partner.getRepresentativeUser() == null ? partner.getContactName() : coalesce(
                partner.getRepresentativeUser().getDisplayName(), partner.getRepresentativeUser().getUsername(),
                partner.getRepresentativeUser().getEmail(), "Usuário " + partner.getRepresentativeUser().getId()
            );

        return new AdminPartnerItem(
            partner.getId(),
            partner.getName(),
            representativeName,
            partner.getRepresentativeUser() == null ? null : partner.getRepresentativeUser().getId(),
            representative,
            partner.getStatus(),
            partner.getCategory(),
            partner.getDescription(),
            partner.getImageUrl(),
            partner.getWebsiteUrl(),
            partner.getContactName(),
            partner.getContactEmail(),
            linkedResource == null ? null : new PartnerLinkedResource(
                linkedResource.getTargetType(),
                linkedResource.getTargetId(),
                linkedResource.getExternalRef(),
                linkedResource.getLabel()
            ),
            partner.getExternalLinks().stream()
                .sorted(Comparator.comparing(PartnerExternalLink::getId, Comparator.nullsLast(Integer::compareTo)))
                .map(link -> new PartnerExternalLinkItem(link.getType(), link.getLabel(), link.getUrl()))
                .toList(),
            metrics == null ? 0 : safeLong(metrics.getClicks()),
            metrics == null ? 0 : safeLong(metrics.getInvites()),
            partner.getCreatedAt() == null ? null : partner.getCreatedAt().toString()
        );
    }

    private Map<Integer, PartnerRepresentative> representativesByUser(List<Partner> partners) {
        List<User> users = partners.stream().map(Partner::getRepresentativeUser)
            .filter(java.util.Objects::nonNull).distinct().toList();
        if (users.isEmpty()) return Map.of();
        List<Integer> userIds = users.stream().map(User::getId).toList();
        Map<Integer, UserDiscord> discordByUser = userDiscordRepository.findByUserIdIn(userIds).stream()
            .collect(Collectors.toMap(value -> value.getUser().getId(), value -> value, (first, ignored) -> first));
        Map<Integer, UserTelegram> telegramByUser = userTelegramRepository.findByUserIdIn(userIds).stream()
            .collect(Collectors.toMap(value -> value.getUser().getId(), value -> value, (first, ignored) -> first));
        return users.stream().collect(Collectors.toMap(User::getId, user -> new PartnerRepresentative(
            user.getId(), coalesce(user.getDisplayName(), user.getUsername(), user.getEmail(), "Usuário " + user.getId()),
            user.getProfileImageUrl(), user.getEmail(), discordUsername(discordByUser.get(user.getId())),
            telegramUsername(telegramByUser.get(user.getId()))
        )));
    }

    private String discordUsername(UserDiscord discord) {
        return discord == null ? null : coalesce(discord.getDisplayName(), discord.getUsername());
    }

    private String telegramUsername(UserTelegram telegram) {
        return telegram == null ? null : coalesce(telegram.getDisplayName(), telegram.getUsername());
    }

    private Partner findPartner(Integer id) {
        return partnerRepository.findWithDetailsById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Parceria nao encontrada"));
    }

    private Map<Integer, PartnerMetricTotalsProjection> metricsByPartner(List<Partner> partners) {
        List<Integer> ids = partners.stream().map(Partner::getId).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return partnerMetricRepository.sumTotalsByPartnerIds(ids)
            .stream()
            .collect(Collectors.toMap(PartnerMetricTotalsProjection::getPartnerId, projection -> projection));
    }

    private PartnerLinkedResource compatibleLinkedResource(PartnerLinkedResource resource, Integer communityId, String representativeName) {
        if (resource != null) {
            if (communityId != null && (!"community".equalsIgnoreCase(resource.type()) || !communityId.equals(resource.id()))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "communityId diverge do linkedResource");
            }
            return resource;
        }
        if (communityId == null) {
            return null;
        }
        return new PartnerLinkedResource("community", communityId, null, representativeName);
    }

    private String uniqueSlug(String value, Integer currentPartnerId) {
        return partnerSlugService.uniqueSlug(required(value, "Slug obrigatorio"), currentPartnerId);
    }

    private String normalizeAndValidate(String value, Collection<String> accepted, String error) {
        String normalized = required(value, error).toLowerCase(Locale.ROOT);
        if (!accepted.contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error);
        }
        return normalized;
    }

    private void requireId(Integer id, String type) {
        if (id == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Vinculo " + type + " exige id interno");
        }
    }

    private String required(String value, String error) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, error);
        }
        return trimmed;
    }

    private String blankToNull(String value) {
        return isBlank(value) ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private String trimToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    private String defaultValue(String value, String fallback) {
        return isBlank(value) ? fallback : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private long safeLong(Number value) {
        return value == null ? 0 : value.longValue();
    }

    private String coalesce(String... values) {
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }
}
