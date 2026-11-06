create table if not exists sessions (
  session_id          text primary key,
  state               text not null check (state in ('working', 'idle')),
  last_seen           timestamptz not null,
  lease_until         timestamptz not null,
  working_since       timestamptz,
  last_user_prompt_at timestamptz,
  last_event          text not null
);

create table if not exists meta (
  key   text primary key,
  value text not null
);

create table if not exists devices (
  token                 text primary key,
  mode                  text not null default 'claude_busy' check (mode in ('claude_busy', 'my_turn')),
  my_turn_minutes       integer not null default 15,
  grace_seconds         integer not null default 10,
  last_push_working     boolean,
  last_push_lease_until timestamptz,
  last_push_at          timestamptz,
  registered_at         timestamptz not null default now()
);
