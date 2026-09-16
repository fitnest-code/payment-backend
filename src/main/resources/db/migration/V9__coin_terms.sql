-- Singleton Coin terms HTML document (AZ/EN/RU)

CREATE TABLE IF NOT EXISTS coin_terms (
    id BIGSERIAL PRIMARY KEY,
    html_content_az TEXT NOT NULL DEFAULT '',
    html_content_en TEXT NOT NULL DEFAULT '',
    html_content_ru TEXT NOT NULL DEFAULT '',
    created_by VARCHAR(255),
    created_date TIMESTAMP,
    last_modified_by VARCHAR(255),
    last_modified_date TIMESTAMP
);
