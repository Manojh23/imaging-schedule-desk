CREATE TABLE scanners (
  id VARCHAR(12) PRIMARY KEY,
  modality VARCHAR(6) NOT NULL CHECK (modality IN ('MRI','CT','XRAY')),
  label TEXT NOT NULL
);
INSERT INTO scanners VALUES ('MRI-01','MRI','MRI Suite 1'),('CT-01','CT','CT Suite 1'),('XR-01','XRAY','X-Ray Room 1');

CREATE TABLE appointments (
  id UUID PRIMARY KEY,
  request_key UUID NOT NULL UNIQUE,
  request_hash CHAR(64) NOT NULL,
  scanner_id VARCHAR(12) NOT NULL REFERENCES scanners(id),
  demo_ref VARCHAR(17) NOT NULL CHECK (demo_ref ~ '^DEMO-[A-Z0-9]{1,12}$'),
  starts_at TIMESTAMPTZ NOT NULL,
  ends_at TIMESTAMPTZ NOT NULL,
  status VARCHAR(10) NOT NULL DEFAULT 'BOOKED' CHECK (status IN ('BOOKED','CANCELLED')),
  version INTEGER NOT NULL DEFAULT 0 CHECK (version >= 0),
  CHECK (ends_at > starts_at)
);
CREATE INDEX booked_scanner_idx ON appointments(scanner_id,starts_at,ends_at) WHERE status='BOOKED';
CREATE INDEX appt_date_idx ON appointments(starts_at, scanner_id);
CREATE TABLE schedule_events (
  seq BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  appointment_id UUID NOT NULL REFERENCES appointments(id),
  action VARCHAR(12) NOT NULL CHECK(action IN ('BOOKED','MOVED','CANCELLED')),
  appointment_version INTEGER NOT NULL,
  at TIMESTAMPTZ NOT NULL DEFAULT now(),
  UNIQUE(appointment_id, appointment_version)
);
