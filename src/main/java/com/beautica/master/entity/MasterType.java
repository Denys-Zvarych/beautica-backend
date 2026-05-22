package com.beautica.master.entity;

public enum MasterType {
    SALON_MASTER,
    INDEPENDENT_MASTER,
    // A SALON_OWNER who also personally provides services inside their PRIMARY salon.
    // Distinct from SALON_MASTER: the owner is NOT an invited member, sees full client
    // data, may confirm/complete their own bookings, and is authorized via salon ownership
    // (salon.owner.id == user.id). Created automatically when the owner's first (primary)
    // salon is registered. Only one SALON_OWNER-type master row may exist per user.
    SALON_OWNER
}
