package com.Brafurries.API.user;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserControllerMemberProfileContractTest {
    @Test
    void memberEndpointsNeverAcceptAUserIdFromTheClient() {
        for (Method method : UserController.class.getDeclaredMethods()) {
            boolean memberEndpoint = method.getName().equals("getMyMemberProfile")
                || method.getName().equals("getMyCommunityContext")
                || method.getName().equals("getMyCommunityModeration");
            if (!memberEndpoint) continue;
            for (var parameter : method.getParameters()) {
                RequestParam requestParam = parameter.getAnnotation(RequestParam.class);
                PathVariable pathVariable = parameter.getAnnotation(PathVariable.class);
                String name = requestParam != null ? requestParam.name() : pathVariable != null ? pathVariable.name() : "";
                assertFalse("userId".equals(name), method.getName() + " must use Authentication, not client supplied userId");
            }
        }
        assertTrue(true);
    }
}
