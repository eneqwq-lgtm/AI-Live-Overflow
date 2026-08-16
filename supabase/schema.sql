-- AI-Live-Overflow Supabase Schema
-- Run these in Supabase SQL Editor

-- Enable UUID extension
create extension if not exists "uuid-ossp";

-- Gesture Log: Track all touch interactions
create table if not exists gesture_log (
    id uuid primary key default uuid_generate_v4(),
    gesture_type text not null check (gesture_type in ('tap', 'double_tap', 'long_press', 'fling', 'drag')),
    x integer,
    y integer,
    velocity_x float,
    velocity_y float,
    created_at timestamptz default now()
);

-- App Usage: Track foreground app changes
create table if not exists app_usage (
    id uuid primary key default uuid_generate_v4(),
    package_name text not null,
    app_name text,
    started_at timestamptz default now(),
    ended_at timestamptz,
    duration_seconds integer
);

-- Pet State: AI can write here to control the pet
create table if not exists pet_state (
    id uuid primary key default uuid_generate_v4(),
    state_key text not null,
    state_value text,
    emotion text,
    accessory text,
    speech_bubble text,
    updated_at timestamptz default now()
);

-- Screenshot Events: Track when user takes screenshots
create table if not exists screenshot_events (
    id uuid primary key default uuid_generate_v4(),
    file_name text,
    detected_at timestamptz default now()
);

-- Heat/Mood System: Emotion tracking
create table if not exists mood_state (
    id uuid primary key default uuid_generate_v4(),
    heat integer default 0 check (heat >= 0 and heat <= 100),
    valence float default 0.5 check (valence >= 0 and valence <= 1),
    arousal float default 0.5 check (arousal >= 0 and arousal <= 1),
    loneliness_level integer default 0,
    last_interaction_at timestamptz default now(),
    updated_at timestamptz default now()
);

-- Notification Whispers: Custom messages
create table if not exists notification_whispers (
    id uuid primary key default uuid_generate_v4(),
    category text not null check (category in ('general', 'morning', 'afternoon', 'evening', 'late_night', 'special')),
    message text not null,
    is_active boolean default true,
    created_at timestamptz default now()
);

-- Insert default whispers
insert into notification_whispers (category, message) values
('general', 'I''m watching you~'),
('general', 'Hello there!'),
('general', 'Pet me!'),
('general', 'Thinking of you~'),
('general', 'Bored?'),
('morning', 'Good morning!'),
('morning', 'Wake up~'),
('morning', 'A new day starts!'),
('afternoon', 'Lunch time!'),
('afternoon', 'Don''t forget to eat~'),
('afternoon', 'Yummy time'),
('evening', 'Good evening~'),
('evening', 'Still working?'),
('late_night', 'Still awake? Go to sleep...'),
('late_night', 'It''s late...'),
('late_night', 'Zzz...');

-- Enable Row Level Security (RLS)
alter table gesture_log enable row level security;
alter table app_usage enable row level security;
alter table pet_state enable row level security;
alter table screenshot_events enable row level security;
alter table mood_state enable row level security;
alter table notification_whispers enable row level security;

-- Create policies for anonymous access (adjust for your needs)
create policy "Allow all for anon" on gesture_log for all using (true);
create policy "Allow all for anon" on app_usage for all using (true);
create policy "Allow all for anon" on pet_state for all using (true);
create policy "Allow all for anon" on screenshot_events for all using (true);
create policy "Allow all for anon" on mood_state for all using (true);
create policy "Allow all for anon" on notification_whispers for all using (true);

-- Create indexes for better performance
create index idx_gesture_log_created_at on gesture_log(created_at desc);
create index idx_app_usage_started_at on app_usage(started_at desc);
create index idx_pet_state_updated_at on pet_state(updated_at desc);

-- Create updated_at trigger function
create or replace function update_updated_at()
returns trigger as $$
begin
    new.updated_at = now();
    return new;
end;
$$ language plpgsql;

-- Apply trigger to tables with updated_at
create trigger update_pet_state_updated_at
    before update on pet_state
    for each row execute function update_updated_at();

create trigger update_mood_state_updated_at
    before update on mood_state
    for each row execute function update_updated_at();

-- Realtime: Enable for tables you want to subscribe to
alter publication supabase_realtime add table pet_state;
alter publication supabase_realtime add table mood_state;

-- AI → Pet 消息表（大脑推送气泡/情绪，桌宠 15 秒轮询）
create table if not exists pet_messages (
    id uuid primary key default uuid_generate_v4(),
    bubble text,
    text text,
    mood text,
    heat integer check (heat >= 0 and heat <= 100),
    created_at timestamptz default now()
);

alter table pet_messages enable row level security;
create policy "Allow all for anon" on pet_messages for all using (true);
alter publication supabase_realtime add table pet_messages;

create index idx_pet_messages_created_at on pet_messages(created_at desc);
