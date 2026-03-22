CREATE TABLE users (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE events (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name VARCHAR(500) NOT NULL,
    venue VARCHAR(500),
    start_date DATETIME,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE seats (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_id BIGINT NOT NULL,
    seat_number VARCHAR(32) NOT NULL,
    grade VARCHAR(32),
    price DECIMAL(12, 2) NOT NULL,
    status VARCHAR(32) NOT NULL,
    UNIQUE KEY uq_event_seat (event_id, seat_number),
    CONSTRAINT fk_seat_event FOREIGN KEY (event_id) REFERENCES events (id)
);

CREATE INDEX idx_seat_event_status ON seats (event_id, status);

CREATE TABLE reservations (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id BIGINT NOT NULL,
    event_id BIGINT NOT NULL,
    seat_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    reserved_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NULL,
    CONSTRAINT fk_res_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_res_event FOREIGN KEY (event_id) REFERENCES events (id),
    CONSTRAINT fk_res_seat FOREIGN KEY (seat_id) REFERENCES seats (id)
);

CREATE INDEX idx_reservation_user ON reservations (user_id);
CREATE INDEX idx_reservation_event ON reservations (event_id);
