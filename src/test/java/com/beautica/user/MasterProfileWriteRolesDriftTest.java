package com.beautica.user;

import com.beautica.auth.Role;
import com.beautica.master.controller.IndependentMasterController;
import com.beautica.master.controller.MasterController;
import com.beautica.master.dto.MasterProfileUpdateRequest;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class MasterProfileWriteRolesDriftTest {

    private static final Pattern ROLE = Pattern.compile("'([A-Z_]+)'");

    @Test
    void should_equalUnionOfControllerRoleGates_when_serviceBackstopDeclared() throws Exception {
        Set<Role> independentGate = rolesOf(IndependentMasterController.class, "updateProfile");
        Set<Role> salonMasterGate = rolesOf(MasterController.class, "updateMyProfile");

        Set<Role> union = EnumSet.noneOf(Role.class);
        union.addAll(independentGate);
        union.addAll(salonMasterGate);

        assertThat(independentGate).isNotEmpty();
        assertThat(salonMasterGate).isNotEmpty();
        assertThat(UserService.MASTER_PROFILE_WRITE_ROLES).isEqualTo(union);
    }

    private static Set<Role> rolesOf(Class<?> controller, String methodName) throws Exception {
        Method m = controller.getMethod(methodName, MasterProfileUpdateRequest.class,
                org.springframework.security.core.Authentication.class);
        PreAuthorize pre = m.getAnnotation(PreAuthorize.class);
        assertThat(pre).as("@PreAuthorize on %s#%s", controller.getSimpleName(), methodName).isNotNull();
        Set<Role> roles = EnumSet.noneOf(Role.class);
        Matcher mt = ROLE.matcher(pre.value());
        while (mt.find()) {
            roles.add(Role.valueOf(mt.group(1)));
        }
        return roles;
    }
}
