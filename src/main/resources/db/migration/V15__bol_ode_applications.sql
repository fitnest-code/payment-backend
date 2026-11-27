-- Kapital Bank "Böl ödə" (provider KAPITAL_BOL_ODE) manual application workflow.
-- Why: the bank has no API yet, so customers submit an application and FitNest admins
-- relay it manually. This is deliberately separate from ABB BNPL and Kapital HPP/Taksit.
-- display_id ("BÖ-{n}") comes from bol_ode_display_seq so support can quote a short, stable id.
CREATE SEQUENCE IF NOT EXISTS bol_ode_display_seq START WITH 1001 INCREMENT BY 1;

CREATE TABLE IF NOT EXISTS bol_ode_applications (
    id BIGSERIAL PRIMARY KEY,
    display_id VARCHAR(32) NOT NULL UNIQUE,
    payment_id BIGINT NULL,
    payment_reference VARCHAR(128) NULL,
    user_id BIGINT NOT NULL,
    -- PII: never logged; masked in admin lists.
    fin_code VARCHAR(16) NOT NULL,
    birth_date DATE NOT NULL,
    extra_phone1 VARCHAR(32),
    extra_phone2 VARCHAR(32),
    contact_phone VARCHAR(32),
    birbank_consent_accepted BOOLEAN NOT NULL,
    mygov_consent_accepted BOOLEAN NOT NULL,
    package_name VARCHAR(255),
    duration_months INT,
    final_amount NUMERIC(12, 2),
    option_id BIGINT NULL,
    package_id BIGINT NULL,
    status VARCHAR(32) NOT NULL,
    decline_reason TEXT,
    decline_reason_visible_to_customer BOOLEAN DEFAULT TRUE,
    assigned_admin_user_id BIGINT,
    internal_note TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_bol_ode_user_status ON bol_ode_applications(user_id, status);
CREATE INDEX IF NOT EXISTS idx_bol_ode_display_id ON bol_ode_applications(display_id);
CREATE INDEX IF NOT EXISTS idx_bol_ode_created_at ON bol_ode_applications(created_at);
CREATE INDEX IF NOT EXISTS idx_bol_ode_status ON bol_ode_applications(status);

-- Append-only audit trail (status changes, admin edits, customer cancel).
CREATE TABLE IF NOT EXISTS bol_ode_application_events (
    id BIGSERIAL PRIMARY KEY,
    application_id BIGINT NOT NULL REFERENCES bol_ode_applications(id),
    actor_type VARCHAR(16) NOT NULL,
    actor_user_id BIGINT,
    from_status VARCHAR(32),
    to_status VARCHAR(32),
    message TEXT,
    decline_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_bol_ode_events_application ON bol_ode_application_events(application_id, created_at);

-- Hard guarantee for "one active application per user" under concurrent inits
-- (the service checks first, this index closes the race).
CREATE UNIQUE INDEX IF NOT EXISTS uq_bol_ode_one_active_per_user
    ON bol_ode_applications(user_id)
    WHERE status IN ('NEW', 'PENDING', 'IN_REVIEW', 'APPROVED');
