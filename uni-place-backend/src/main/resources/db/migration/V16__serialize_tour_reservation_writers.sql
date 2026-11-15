-- One transactional lock shared by every application instance writing tours.
-- Existing reservations, telephone representations and inactive history are unchanged.
CREATE TABLE tour_reservation_write_guard (
    guard_id SMALLINT NOT NULL PRIMARY KEY,
    CONSTRAINT ck_tour_reservation_write_guard_singleton CHECK (guard_id = 1)
) ENGINE=InnoDB;

INSERT INTO tour_reservation_write_guard (guard_id) VALUES (1);
