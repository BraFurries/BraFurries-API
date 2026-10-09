package com.Brafurries.API.entity.portaria;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "portaria_base_config")
public class PortariaBaseConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "server_guild_id", nullable = false)
    private Long serverGuildId;

    @Column(name = "acesso_provisorio_role_id")
    private Long acessoProvisorioRoleId;

    @Column(name = "visitante_role_id")
    private Long visitanteRoleId;

    @Column(name = "maior_18_role_id")
    private Long maior18RoleId;

    @Column(name = "menor_18_role_id")
    private Long menor18RoleId;

    @Column(name = "formulario_portaria_ativo", nullable = false)
    private Boolean formularioPortariaAtivo;

    @Column(name = "portaria_enabled", nullable = false)
    private Boolean portariaEnabled;

    @Column(name = "idade_minima_conta_dias", nullable = false)
    private Integer idadeMinimaContaDias;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
