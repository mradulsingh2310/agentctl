create table tickets (
    tenant_id text not null,
    id text not null,
    run_id text not null,
    backend text not null,
    external_ticket_id text not null,
    external_url text,
    title text not null,
    body text not null,
    status text not null,
    severity text not null,
    labels_json text not null,
    assignee text,
    idempotency_marker text not null,
    created_at timestamp not null,
    updated_at timestamp not null,
    primary key (tenant_id, id),
    constraint tickets_run_fk foreign key (tenant_id, run_id) references runs (tenant_id, id) on delete cascade,
    constraint tickets_backend_check check (backend in ('fake', 'github')),
    constraint tickets_status_check check (status in ('OPEN', 'CLOSED'))
);

create table fake_tickets (
    tenant_id text not null,
    id text not null,
    title text not null,
    body text not null,
    status text not null,
    severity text not null,
    labels_json text not null,
    assignee text,
    idempotency_marker text not null,
    created_at timestamp not null,
    updated_at timestamp not null,
    primary key (tenant_id, id),
    constraint fake_tickets_tenant_fk foreign key (tenant_id) references tenants (id) on delete cascade,
    constraint fake_tickets_status_check check (status in ('OPEN', 'CLOSED'))
);

create table fake_ticket_events (
    tenant_id text not null,
    id text not null,
    fake_ticket_id text not null,
    event_type text not null,
    event_json text not null,
    created_at timestamp not null,
    primary key (tenant_id, id),
    constraint fake_ticket_events_ticket_fk
        foreign key (tenant_id, fake_ticket_id) references fake_tickets (tenant_id, id) on delete cascade
);

create table tool_idempotency_records (
    tenant_id text not null,
    operation_id text not null,
    tool_name text not null,
    status text not null,
    request_hash text not null,
    response_json text,
    created_at timestamp not null,
    updated_at timestamp not null,
    primary key (tenant_id, operation_id),
    constraint tool_idempotency_records_tenant_fk foreign key (tenant_id) references tenants (id) on delete cascade,
    constraint tool_idempotency_records_status_check check (status in ('IN_PROGRESS', 'COMPLETED', 'FAILED'))
);

create index tickets_tenant_run_created_at_idx on tickets (tenant_id, run_id, created_at);
create index tickets_tenant_backend_external_ticket_idx on tickets (tenant_id, backend, external_ticket_id);
create index fake_tickets_tenant_created_at_idx on fake_tickets (tenant_id, created_at);
create index fake_ticket_events_tenant_ticket_created_at_idx on fake_ticket_events (tenant_id, fake_ticket_id, created_at);
create index tool_idempotency_records_tenant_tool_status_idx
    on tool_idempotency_records (tenant_id, tool_name, status, updated_at);
