import * as grpc from '@grpc/grpc-js';
import * as protoLoader from '@grpc/proto-loader';
import path from 'path';

const PROTO_DIR = path.join(__dirname, '../../../shared/proto');

function loadService(protoFileName: string, packageName: string, serviceName: string, address: string): any {
  const protoPath = path.join(PROTO_DIR, protoFileName);
  const packageDefinition = protoLoader.loadSync(protoPath, {
    keepCase: true,
    longs: String,
    enums: String,
    defaults: true,
    oneofs: true,
  });
  
  const protoDescriptor = grpc.loadPackageDefinition(packageDefinition);
  
  // Drill down into package names (nested by dot)
  let service: any = protoDescriptor;
  for (const part of packageName.split('.')) {
    if (service) service = service[part];
  }
  
  if (!service || !service[serviceName]) {
    throw new Error(`gRPC Service ${packageName}.${serviceName} not found in ${protoFileName}`);
  }
  
  return new service[serviceName](
    address,
    grpc.credentials.createInsecure() // mTLS can be enabled here in production
  );
}

// Instantiate client connections (using standard local port mappings)
export const paymentClient = loadService('payment.proto', 'paycore.payment.v1', 'PaymentService', 'localhost:50051');
export const authClient = loadService('auth.proto', 'paycore.auth.v1', 'AuthService', 'localhost:50056');
export const fraudClient = loadService('fraud.proto', 'paycore.fraud.v1', 'FraudDetectionService', 'localhost:50054');
export const webhookClient = loadService('webhook.proto', 'paycore.webhook.v1', 'WebhookService', 'localhost:50055');
export const ledgerClient = loadService('ledger.proto', 'paycore.ledger.v1', 'LedgerService', 'localhost:50053');
