package sn.samaadresse.report;

public enum ReportStatus {
    OPEN,
    /** Signalement écarté : l'adresse reste en ligne. */
    DISMISSED,
    /** Signalement fondé : l'adresse a été archivée. */
    ACTION_TAKEN
}
