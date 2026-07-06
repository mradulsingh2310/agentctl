create table tenants (
    id text primary key,
    display_name text not null,
    created_at timestamp not null
);

create table users (
    tenant_id text not null,
    id text not null,
    display_name text not null,
    created_at timestamp not null,
    primary key (tenant_id, id),
    constraint users_tenant_fk foreign key (tenant_id) references tenants (id) on delete cascade
);

create table runs (
    tenant_id text not null,
    id text not null,
    agent_id text not null,
    status text not null,
    input text not null,
    created_at timestamp not null,
    updated_at timestamp not null,
    primary key (tenant_id, id),
    constraint runs_tenant_fk foreign key (tenant_id) references tenants (id) on delete cascade,
    constraint runs_status_check check (
        status in (
            'CREATED',
            'RUNNING',
            'WAITING_FOR_APPROVAL',
            'APPROVED',
            'REJECTED',
            'FAILED',
            'COMPLETED',
            'CANCELLED'
        )
    )
);

create table approvals (
    tenant_id text not null,
    id text not null,
    run_id text not null,
    status text not null,
    tool_name text not null,
    question text not null,
    created_at timestamp not null,
    decided_at timestamp,
    decided_by text,
    decision_reason text,
    primary key (tenant_id, id),
    constraint approvals_run_fk foreign key (tenant_id, run_id) references runs (tenant_id, id) on delete cascade,
    constraint approvals_status_check check (status in ('PENDING', 'APPROVED', 'REJECTED'))
);

create table audit_events (
    tenant_id text not null,
    id text not null,
    run_id text not null,
    event_type text not null,
    message text not null,
    created_at timestamp not null,
    primary key (tenant_id, id),
    constraint audit_events_run_fk foreign key (tenant_id, run_id) references runs (tenant_id, id) on delete cascade
);

create index runs_tenant_created_at_idx on runs (tenant_id, created_at);
create index approvals_tenant_status_created_at_idx on approvals (tenant_id, status, created_at);
create index audit_events_tenant_run_created_at_idx on audit_events (tenant_id, run_id, created_at);
