# Payment Service Idempotency Implementation - Summary

## ✅ What Was Implemented

### 1. Database Entities

#### `IdempotencyKey.java`
- **Location**: `src/main/java/az/fitnest/payment/model/entity/IdempotencyKey.java`
- **Purpose**: Stores idempotency keys with cached payment responses
- **Key Fields**:
  - `idempotencyKey` (unique): The client-provided idempotency key
  - `paymentId`: Reference to the payment (nullable)
  - `responseStatus`, `responseTransactionId`, `responseOrderId`: Quick lookup fields
  - `responseBody`: Full JSON response cached
  - `createdAt`, `expiresAt`: Lifecycle management (24-hour default expiry)

#### Updated `Payment.java`
- **New Fields Added**:
  - `userId`: Track which user made the payment
  - `description`: Payment description
  - `redirectUrl`: Store redirect URL from Epoint
  - `createdAt`, `updatedAt`: Audit timestamps with JPA lifecycle hooks

### 2. Repository

#### `IdempotencyKeyRepository.java`
- **Location**: `src/main/java/az/fitnest/payment/repository/IdempotencyKeyRepository.java`
- **Methods**:
  - `findByIdempotencyKey()`: Look up cached responses
  - `deleteExpiredKeys()`: Clean up old entries

### 3. Service Layer

#### `IdempotencyService.java`
- **Location**: `src/main/java/az/fitnest/payment/service/IdempotencyService.java`
- **Key Methods**:
  - `getCachedResponse()`: Check if idempotency key exists and return cached response
  - `storeResponse()`: Save response with idempotency key for future lookups
  - `cleanupExpiredKeys()`: Scheduled task (runs hourly) to remove expired keys

#### Updated `EpointIntegrationService.java`
- **Enhanced Methods** with idempotency support:
  - `initiatePayment()` - Now checks for duplicate orderId and cached responses
  - `cardRegistration()` - Prevents duplicate card registrations
  - `executePay()` - Prevents duplicate payment execution
  - `cardRegistrationWithPay()` - Combines card registration with payment idempotently

- **New Helper Methods**:
  - `buildResponseFromPayment()`: Constructs EpointResponse from existing Payment entity
  - Enhanced `savePaymentIfSuccess()`: Now stores userId, description, and returns Payment object

### 4. Controller Layer

#### Updated `EpointController.java`
- **Added `Idempotency-Key` header support** to key endpoints:
  - `POST /epoint/request` - Initiate payment
  - `POST /epoint/card-registration` - Register card
  - `POST /epoint/execute-pay` - Execute payment with saved card
  - `POST /epoint/card-registration-with-pay` - Register card and pay

- **Header**: `@RequestHeader(value = "Idempotency-Key", required = false)`

### 5. Application Configuration

#### `PaymentServiceApplication.java`
- **Added**: `@EnableScheduling` annotation for scheduled cleanup tasks

## 🔑 How It Works

### Idempotency Flow

```
Client sends request with Idempotency-Key header
              ↓
   Check if key exists in database
              ↓
         ┌────┴────┐
         │         │
       YES        NO
         │         │
         │         └→ Process with Epoint
         │            Save payment to DB
         │            Cache response with key
         │            Return response
         │
         └→ Return cached response (immediate)
```

### Duplicate Prevention

The service provides **two layers** of duplicate prevention:

1. **Idempotency Key**: Database-level caching of responses
2. **Order ID Check**: Prevents duplicate payments even without idempotency key

## 📝 Usage Examples

### Example 1: Payment with Idempotency Key

```bash
curl -X POST https://api.fitnest.az/epoint/request \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -H "Idempotency-Key: 550e8400-e29b-41d4-a716-446655440000" \
  -d '{
    "orderId": "ORDER-12345",
    "amount": 29.99,
    "currency": "AZN",
    "description": "Premium Subscription"
  }'
```

**First Request**: Processes normally, stores response
**Retry (same key)**: Returns cached response immediately (no Epoint call)

### Example 2: Card Registration

```bash
curl -X POST https://api.fitnest.az/epoint/card-registration \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -H "Idempotency-Key: 660e8400-e29b-41d4-a716-446655440001" \
  -d '{
    "orderId": "CARD-REG-789",
    "amount": 1.00,
    "currency": "AZN"
  }'
```

## 🗄️ Database Schema

### Table: `idempotency_keys`

```sql
CREATE TABLE idempotency_keys (
    id BIGSERIAL PRIMARY KEY,
    idempotency_key VARCHAR(255) NOT NULL UNIQUE,
    payment_id BIGINT REFERENCES payments(payment_id),
    response_status VARCHAR(50),
    response_transaction_id VARCHAR(255),
    response_order_id VARCHAR(255),
    response_body TEXT,
    created_at TIMESTAMP NOT NULL,
    expires_at TIMESTAMP NOT NULL
);

CREATE UNIQUE INDEX idx_idempotency_key ON idempotency_keys(idempotency_key);
CREATE INDEX idx_created_at ON idempotency_keys(created_at);
```

### Table: `payments` (Updated)

```sql
ALTER TABLE payments ADD COLUMN user_id BIGINT;
ALTER TABLE payments ADD COLUMN description VARCHAR(500);
ALTER TABLE payments ADD COLUMN redirect_url VARCHAR(500);
ALTER TABLE payments ADD COLUMN created_at TIMESTAMP;
ALTER TABLE payments ADD COLUMN updated_at TIMESTAMP;

CREATE INDEX idx_payments_order_id ON payments(order_id);
```

## 🎯 Key Features

### 1. Safe Retries
- Network failures won't cause duplicate charges
- Client can safely retry requests with same idempotency key

### 2. Performance
- Cached responses served immediately without Epoint API call
- Indexed lookups for fast retrieval

### 3. Automatic Cleanup
- Expired keys cleaned up hourly (24-hour expiry)
- No manual maintenance required

### 4. Order ID Protection
- Additional safety: checks if orderId already exists
- Returns existing payment info if found

### 5. Card Management
- Saved cards stored with masked details
- Brand detection (Visa, Mastercard, etc.)
- Auto-set first card as default

### 6. Full Audit Trail
- All payments tracked with timestamps
- User association for accountability
- Complete response history

## 📊 Data Stored

### For Each Payment:
- ✅ User ID
- ✅ Order ID (unique)
- ✅ Transaction ID from Epoint
- ✅ Amount & Currency
- ✅ Card details (masked)
- ✅ RRN (Retrieval Reference Number)
- ✅ Bank transaction reference
- ✅ Payment status
- ✅ Redirect URL
- ✅ Description
- ✅ Created/Updated timestamps

### For Each Saved Card:
- ✅ User ID
- ✅ Card ID from Epoint
- ✅ Masked card number
- ✅ Card name
- ✅ Brand (auto-detected)
- ✅ Default flag

## 🚀 Next Steps

1. **Run the SQL schema updates** on your database (see schema section above)
2. **Deploy the updated service**
3. **Update client applications** to send `Idempotency-Key` header
4. **Monitor logs** for idempotency key usage

## 📌 Best Practices

### For API Clients:
1. Always include `Idempotency-Key` header for payment operations
2. Use UUID format for keys
3. Keep the same key when retrying failed requests
4. Generate new key for each distinct payment

### For Monitoring:
- Watch for duplicate key usage patterns in logs
- Monitor `idempotency_keys` table size
- Check cleanup job execution in logs

## ✨ Benefits Summary

- ✅ **Prevents duplicate payments** - No accidental double charges
- ✅ **Safe retries** - Network issues won't cause problems
- ✅ **Better tracking** - Complete payment history with user info
- ✅ **Saved cards** - Store and reuse customer payment methods
- ✅ **Automatic cleanup** - Self-maintaining system
- ✅ **Production-ready** - Battle-tested idempotency pattern

---

**Implementation Date**: March 6, 2026
**Status**: ✅ Complete and tested
**Build Status**: ✅ Successful

