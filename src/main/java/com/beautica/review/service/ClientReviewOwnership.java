package com.beautica.review.service;

import com.beautica.auth.Role;
import com.beautica.common.security.AuthorizationService;
import com.beautica.master.entity.Master;
import com.beautica.review.entity.ClientReview;
import com.beautica.salon.entity.Salon;
import com.beautica.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * B570 (REV-45, REV-D12) — the entity form of the REV-44 {@code own} predicate the comments page computes in SQL
 * ({@code ClientRatingSql.OWN_*}), so the «Ваш коментар» mark and the delete authority are the same rule:
 * <ul>
 *   <li>owner / admin: the rating's salon is one they manage (any salon member's rating is the salon's, REV-42);</li>
 *   <li>independent master: they are the rating's master and it has no salon;</li>
 *   <li>salon master: they are the rating's master, i.e. they performed its booking (the author master is always
 *       the booking's master), and that master row and its salon are live — the same gate as rating
 *       ({@link AuthorizationService#isLivePerformingSalonMaster}).</li>
 * </ul>
 * Any other role (CLIENT, null) is never own.
 */
@Component
@RequiredArgsConstructor
public class ClientReviewOwnership {

    private final AuthorizationService authz;

    public boolean isOwn(UUID actorId, Role actorRole, ClientReview review) {
        if (actorId == null || actorRole == null) {
            return false;
        }
        Salon salon = review.getSalon();
        return switch (actorRole) {
            case SALON_OWNER, SALON_ADMIN -> salon != null && authz.hasManagementAccess(salon.getId(), actorId);
            case INDEPENDENT_MASTER -> salon == null && isAuthor(actorId, review);
            case SALON_MASTER -> review.getAuthorMaster() != null
                    && AuthorizationService.isLivePerformingSalonMaster(actorId, review.getAuthorMaster());
            default -> false;
        };
    }

    private static boolean isAuthor(UUID actorId, ClientReview review) {
        Master master = review.getAuthorMaster();
        User user = master == null ? null : master.getUser();
        return user != null && actorId.equals(user.getId());
    }
}
