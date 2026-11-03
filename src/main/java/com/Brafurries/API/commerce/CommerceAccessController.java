package com.Brafurries.API.commerce;

import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Private BraFurries operations; intentionally not under the Community routes.
 * All grants and role listings are owner-only regardless of Community Admin role.
 */
@RestController
@RequestMapping("/user/me/brafurries-commerce/access")
public class CommerceAccessController {
    private final CommerceAccessService service;

    public CommerceAccessController(CommerceAccessService service) {
        this.service = service;
    }

    @GetMapping("/me")
    public CommerceAccessService.AccessView me(Authentication auth) {
        return service.myAccess(auth);
    }

    @GetMapping("/people")
    public List<CommerceAccessService.PersonView> people(Authentication auth) {
        return service.listPeople(auth);
    }

    @GetMapping("/team-roles")
    public List<CommerceAccessService.RoleView> roles(Authentication auth) {
        return service.listRoles(auth);
    }

    @PutMapping("/people/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void approve(Authentication auth, @PathVariable int userId) {
        service.approvePerson(auth, userId);
    }

    @DeleteMapping("/people/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(Authentication auth, @PathVariable int userId) {
        service.revokePerson(auth, userId);
    }

    @PutMapping("/people/{userId}/permissions/{code}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void grantPerson(Authentication auth, @PathVariable int userId, @PathVariable String code) {
        service.grantPersonPermission(auth, userId, code);
    }

    @DeleteMapping("/people/{userId}/permissions/{code}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokePerson(Authentication auth, @PathVariable int userId, @PathVariable String code) {
        service.revokePersonPermission(auth, userId, code);
    }

    @PutMapping("/team-roles/{roleId}/permissions/{code}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void grantRole(Authentication auth, @PathVariable int roleId, @PathVariable String code) {
        service.grantRolePermission(auth, roleId, code);
    }

    @DeleteMapping("/team-roles/{roleId}/permissions/{code}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeRole(Authentication auth, @PathVariable int roleId, @PathVariable String code) {
        service.revokeRolePermission(auth, roleId, code);
    }
}
