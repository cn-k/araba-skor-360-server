CREATE TABLE user_car_review (
    id BIGSERIAL PRIMARY KEY,
    model_variant_id INTEGER NOT NULL REFERENCES model_variant(id),
    user_id TEXT NOT NULL,
    firebase_uid TEXT NOT NULL,
    display_name TEXT,
    avatar_url TEXT,
    score INTEGER NOT NULL CHECK (score BETWEEN 1 AND 100),
    comment TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_user_car_review_variant_user UNIQUE (model_variant_id, user_id)
);

CREATE INDEX idx_user_car_review_variant ON user_car_review(model_variant_id);
