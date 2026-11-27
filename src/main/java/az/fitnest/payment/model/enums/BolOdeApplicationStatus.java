package az.fitnest.payment.model.enums;

import java.util.EnumSet;
import java.util.Set;

/** Lifecycle of a manual Kapital "Böl ödə" application. */
public enum BolOdeApplicationStatus {
    NEW, PENDING, IN_REVIEW, APPROVED, DECLINED, CANCELLED;

    /** Statuses that block a second application for the same user. */
    public static final Set<BolOdeApplicationStatus> ACTIVE =
            EnumSet.of(NEW, PENDING, IN_REVIEW, APPROVED);

    /** Statuses the customer may still cancel (APPROVED is already committed with the bank). */
    public static final Set<BolOdeApplicationStatus> CANCELLABLE =
            EnumSet.of(NEW, PENDING, IN_REVIEW);

    /** Statuses where the 15-minute customer wait SLA is still meaningful. */
    public static final Set<BolOdeApplicationStatus> AWAITING_DECISION =
            EnumSet.of(NEW, PENDING, IN_REVIEW);

    public boolean isTerminal() {
        return this == DECLINED || this == CANCELLED;
    }
}
