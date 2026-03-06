# Payment Service - User Endpoints Documentation

This document describes the custom endpoints for managing saved cards and payment information (not related to Epoint integration).

## Table of Contents
- [Saved Cards Management](#saved-cards-management)
- [Payment Information](#payment-information)
- [DTOs](#dtos)

---

## Saved Cards Management

### Base URL: `/api/v1/me/cards`

All endpoints in this section require authentication. The user ID is automatically extracted from the authentication token.

### 1. Get All Saved Cards

**Endpoint:** `GET /api/v1/me/cards`

**Description:** Retrieves all saved cards for the authenticated user with complete card information including:
- **Masked card numbers** (for security, only first 6 and last 4 digits visible)
- **Card brand/provider** (VISA, MASTERCARD, AMERICAN EXPRESS, etc.)
- **Cardholder name**
- **Default card indicator**
- **Creation and update timestamps**

**Response:** `200 OK`
```json
[
  {
    "id": 1,
    "cardId": "card_abc123",
    "cardMask": "424242****4242",
    "cardName": "John Doe",
    "brand": "VISA",
    "isDefault": true,
    "createdAt": "2024-01-15T10:30:00Z",
    "updatedAt": "2024-01-15T10:30:00Z"
  },
  {
    "id": 2,
    "cardId": "card_def456",
    "cardMask": "555555****4444",
    "cardName": "John Doe",
    "brand": "MASTERCARD",
    "isDefault": false,
    "createdAt": "2024-02-20T14:20:00Z",
    "updatedAt": "2024-02-20T14:20:00Z"
  },
  {
    "id": 3,
    "cardId": "card_ghi789",
    "cardMask": "378282****0005",
    "cardName": "Jane Smith",
    "brand": "AMERICAN_EXPRESS",
    "isDefault": false,
    "createdAt": "2024-03-01T08:45:00Z",
    "updatedAt": "2024-03-01T08:45:00Z"
  }
]
```

**Field Descriptions:**
- `id`: Internal database ID for the card record
- `cardId`: External card identifier from the payment provider
- `cardMask`: Masked card number showing only first 6 and last 4 digits (e.g., "424242****4242")
- `cardName`: Name on the card (cardholder name)
- `brand`: Card network/provider (VISA, MASTERCARD, AMERICAN_EXPRESS, DISCOVER, etc.)
- `isDefault`: Boolean indicating if this is the user's default payment method
- `createdAt`: Timestamp when the card was first saved
- `updatedAt`: Timestamp of the last modification

---

### 2. Get Default Card

**Endpoint:** `GET /api/v1/me/cards/default`

**Description:** Retrieves the user's default card.

**Response:** `200 OK` (with card data) or `204 No Content` (if no default card)
```json
{
  "id": 1,
  "cardId": "card_abc123",
  "cardMask": "424242****4242",
  "cardName": "John Doe",
  "brand": "VISA",
  "isDefault": true,
  "createdAt": "2024-01-15T10:30:00Z",
  "updatedAt": "2024-01-15T10:30:00Z"
}
```

---

### 3. Set Default Card

**Endpoint:** `PUT /api/v1/me/cards/default`

**Description:** Sets a specific card as the default card for the user.

**Request Body:**
```json
{
  "cardId": 2
}
```

**Response:** `200 OK`
```json
{
  "id": 2,
  "cardId": "card_def456",
  "cardMask": "555555****4444",
  "cardName": "John Doe",
  "brand": "MASTERCARD",
  "isDefault": true,
  "createdAt": "2024-02-20T14:20:00Z",
  "updatedAt": "2024-03-06T09:15:00Z"
}
```

**Error Responses:**
- `400 Bad Request` - Invalid request body
- `404 Not Found` - Card not found
- `403 Forbidden` - Card does not belong to user

---

### 4. Delete Card

**Endpoint:** `DELETE /api/v1/me/cards/{cardId}`

**Description:** Deletes a saved card. If the deleted card was the default card, automatically sets another card as default.

**Path Parameters:**
- `cardId` (Long) - The ID of the card to delete

**Response:** `204 No Content`

**Error Responses:**
- `404 Not Found` - Card not found
- `403 Forbidden` - Card does not belong to user

---

## Payment Information

### Base URL: `/api/v1/payments`

### 1. Get My Payments

**Endpoint:** `GET /api/v1/payments/me`

**Description:** Retrieves all payment records for the authenticated user.

**Response:** `200 OK`
```json
[
  {
    "paymentId": 1,
    "provider": "EPOINT",
    "status": "NEW",
    "orderId": "ORD123456",
    "transactionId": "txn_abc123",
    "amount": 50.00,
    "currency": "AZN",
    "cardMask": "424242****4242",
    "cardName": "John Doe",
    "message": "Payment initiated",
    "userId": 100,
    "description": "Gym membership payment",
    "createdAt": "2024-03-01T10:00:00Z",
    "updatedAt": "2024-03-01T10:00:00Z"
  }
]
```

---

### 2. Get Payment by ID

**Endpoint:** `GET /api/v1/payments/{paymentId}`

**Description:** Retrieves a specific payment by its ID.

**Path Parameters:**
- `paymentId` (Long) - The payment ID

**Response:** `200 OK`
```json
{
  "paymentId": 1,
  "provider": "EPOINT",
  "status": "NEW",
  "orderId": "ORD123456",
  "transactionId": "txn_abc123",
  "amount": 50.00,
  "currency": "AZN",
  "cardMask": "424242****4242",
  "cardName": "John Doe",
  "message": "Payment initiated",
  "userId": 100,
  "description": "Gym membership payment",
  "createdAt": "2024-03-01T10:00:00Z",
  "updatedAt": "2024-03-01T10:00:00Z"
}
```

**Error Response:**
- `404 Not Found` - Payment not found

---

### 3. Get Payment by Order ID

**Endpoint:** `GET /api/v1/payments/order/{orderId}`

**Description:** Retrieves a payment by order ID.

**Path Parameters:**
- `orderId` (String) - The order ID

**Response:** `200 OK` (same structure as above)

**Error Response:**
- `404 Not Found` - Payment not found

---

### 4. Get Payment by Transaction ID

**Endpoint:** `GET /api/v1/payments/transaction/{transactionId}`

**Description:** Retrieves a payment by transaction ID.

**Path Parameters:**
- `transactionId` (String) - The transaction ID

**Response:** `200 OK` (same structure as above)

**Error Response:**
- `404 Not Found` - Payment not found

---

### 5. Get All Payments (Admin Only)

**Endpoint:** `GET /api/v1/payments/all`

**Description:** Retrieves all payments in the system. Intended for admin use.

**Note:** In production, add role-based authorization (e.g., `@PreAuthorize("hasRole('ADMIN')"`))

**Response:** `200 OK` - Array of payment objects

---

## DTOs

### UserCardResponse
```java
{
  "id": Long,                    // Card record ID
  "cardId": String,              // External card ID from payment provider
  "cardMask": String,            // Masked card number (e.g., "424242****4242")
  "cardName": String,            // Cardholder name
  "brand": String,               // Card brand (VISA, MASTERCARD, AMERICAN_EXPRESS, DISCOVER, etc.)
  "isDefault": Boolean,          // Whether this is the default card
  "createdAt": Instant,          // Creation timestamp
  "updatedAt": Instant           // Last update timestamp
}
```

**Supported Card Brands:**
The `brand` field can contain the following values:
- `VISA` - Visa cards (starts with 4)
- `MASTERCARD` - Mastercard (starts with 51-55 or 2221-2720)
- `AMERICAN_EXPRESS` - American Express (starts with 34 or 37)
- `DISCOVER` - Discover cards (starts with 6011, 622126-622925, 644-649, 65)
- `UNKNOWN` - Card brand could not be determined

The card brand is automatically detected based on the card number pattern.

### PaymentResponse
```java
{
  "paymentId": Long,             // Payment record ID
  "provider": String,            // Payment provider (EPOINT, STRIPE, etc.)
  "status": String,              // Payment status (NEW, PENDING, SUCCESS, FAILED, etc.)
  "orderId": String,             // Associated order ID
  "transactionId": String,       // Provider transaction ID
  "amount": Double,              // Payment amount
  "currency": String,            // Currency code (AZN, USD, etc.)
  "cardMask": String,            // Masked card number used
  "cardName": String,            // Cardholder name
  "message": String,             // Payment message/description
  "userId": Long,                // User who made the payment
  "description": String,         // Additional payment description
  "createdAt": Instant,          // Creation timestamp
  "updatedAt": Instant           // Last update timestamp
}
```

### SetDefaultCardRequest
```java
{
  "cardId": Long                 // Card ID to set as default (required)
}
```

---

## Security Notes

1. **Authentication Required:** All endpoints require a valid JWT token
2. **User Isolation:** Users can only access their own cards and payments
3. **Card Masking:** All card numbers are masked for security (e.g., "424242****4242")
4. **Admin Endpoints:** The `/api/v1/payments/all` endpoint should be restricted to admin roles in production

---

## Error Codes

- `200 OK` - Request successful
- `204 No Content` - Request successful with no response body
- `400 Bad Request` - Invalid request data
- `401 Unauthorized` - Authentication required
- `403 Forbidden` - Insufficient permissions
- `404 Not Found` - Resource not found

---

## Usage Examples

### cURL Examples

#### Get all saved cards:
```bash
curl -X GET "http://localhost:8080/api/v1/me/cards" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"
```

#### Set default card:
```bash
curl -X PUT "http://localhost:8080/api/v1/me/cards/default" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"cardId": 2}'
```

#### Delete a card:
```bash
curl -X DELETE "http://localhost:8080/api/v1/me/cards/1" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"
```

#### Get my payments:
```bash
curl -X GET "http://localhost:8080/api/v1/payments/me" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"
```

#### Get payment by order ID:
```bash
curl -X GET "http://localhost:8080/api/v1/payments/order/ORD123456" \
  -H "Authorization: Bearer YOUR_JWT_TOKEN"
```

---

## Integration Notes

1. **Automatic Card Saving:** Cards are automatically saved when users complete payments through the Epoint integration
2. **Default Card Logic:** The first card saved becomes the default; when a default card is deleted, the next available card becomes default
3. **Payment Status Tracking:** All payment statuses are synchronized with the payment provider
4. **User Association:** Payments and cards are always associated with the authenticated user

---

## Future Enhancements

- [ ] Add pagination for payment list endpoints
- [ ] Add filtering and sorting options
- [ ] Implement card verification status
- [ ] Add payment refund endpoints
- [ ] Implement role-based access control for admin endpoints
- [ ] Add payment statistics endpoints

