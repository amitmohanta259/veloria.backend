package com.app.master.service.core.enums;

public enum Role {
    MD("MD"),
    PA("PA"),
    PSYD("PSYD"),
    LCSW("LCSW"),
    NP("NP"),
    RN("RN"),
    BHNP("BHNP"),
    FNP("FNP"),
    RD("RD"),
    NONE("None"),
    UNKNOWN("UNKNOWN"),
    CASE_MANAGER("Case Manager"),
    FRONT_DESK_MANAGER("Front Desk Manager"),
    GENERAL_MEDICINE("General Medicine"),
    OTHER("Other"),
    NPS("NP, S"),
    NURSE("Nurse"),
    DOCTOR("Doctor"),
    RECEPTIONIST("Receptionist"),
    PHARMACIST("Pharmacist"),
    LAB_TECHNICIAN("LabTechnician"),
    PROVIDER_GROUP_ADMIN("ProviderGroupAdmin"),
    BILLER("Biller"),
    TSS("Tss"),
    STAFF("Staff"),
    FRONT_DESK("FrontDesk"),
    THERAPIST("Therapist"),
    SURGEON("Surgeon"),
    PHYSICIAN("Physician"),
    RADIOLOGIST("Radiologist"),
    CARDIOLOGIST("Cardiologist"),
    NEUROLOGIST("Neurologist"),
    GYNECOLOGIST("Gynecologist"),
    PEDIATRICIAN("Pediatrician"),
    ANESTHESIOLOGIST("Anesthesiologist"),
    SUPER_ADMIN("Super Admin");

    private final String name;

    Role(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

}