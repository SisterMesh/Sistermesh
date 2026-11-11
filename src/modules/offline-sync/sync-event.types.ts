export enum SyncEventType {
  REQUEST_CREATE = 'request.create',
  REQUEST_UPDATE = 'request.update',
  DONOR_RESPONSE_ACCEPT = 'donor-response.accept',
  DONOR_RESPONSE_DECLINE = 'donor-response.decline',
  DONOR_WITHDRAW = 'donor.withdraw',
  REQUEST_CANCEL = 'request.cancel',
}

export interface SyncEventEnvelope {
  eventId: string;
  eventType: string;
  entityId?: string | null;
  originDeviceId: string;
  localCreatedAt: string;
  payloadSchemaVersion: number;
  correlationId?: string;
  payload: Record<string, unknown>;
}

export interface SyncEventResult {
  eventId: string | null;
  status: 'RECEIVED' | 'PROCESSED' | 'REJECTED' | 'CONFLICT';
  errorCode?: string;
  explanation?: string;
  entityId?: string;
}
