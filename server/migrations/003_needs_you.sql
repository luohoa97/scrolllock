-- "Waiting on you": a session (or the coordinator) is blocked on the human. All nullable.
-- needs_label (e.g. a project name) is only ever filled when the sender has opted in to sharing text.
alter table sessions add column if not exists needs_you_at timestamptz;
alter table sessions add column if not exists needs_reason text;
alter table sessions add column if not exists needs_label  text;
