-- Optional agent label and task progress reported by the hooks. All nullable, so old rows and old hooks keep working.
-- task_now / task_next are only ever filled when the sender has opted in to sharing task text.
alter table sessions add column if not exists agent       text;
alter table sessions add column if not exists tasks_done  integer;
alter table sessions add column if not exists tasks_total integer;
alter table sessions add column if not exists task_now    text;
alter table sessions add column if not exists task_next   text;
