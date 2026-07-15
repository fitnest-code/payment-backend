package az.fitnest.payment.exception;

import org.springframework.http.HttpStatus;

/**
 * ABB ödəniş endpoint-ləri texniki işlər rejimindəykən throw edilir.
 *
 * <p>Bu exception {@link GlobalExceptionHandler}-ə düşdükdə aşağıdakı formatı qaytarır:
 * <pre>
 * {
 *   "error": {
 *     "code": "NOT_FOUND",
 *     "message": "Hazırda texniki işlər aparılır. Xidmət tezliklə istifadəyə veriləcək",
 *     "status": 404
 *   }
 * }
 * </pre>
 * {@code NOT_FOUND} kodu üçün messages.properties-də açar olmadığından
 * konstruktorda ötürülən mesaj birbaşa istifadə olunur.</p>
 */
public class AbbMaintenanceException extends BaseException {

    private static final long serialVersionUID = 1L;

    public AbbMaintenanceException(String message) {
        super(message, "NOT_FOUND", HttpStatus.NOT_FOUND);
    }
}
