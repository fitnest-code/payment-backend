package az.fitnest.payment.client;

import az.fitnest.user.grpc.GetUserByIdRequest;
import az.fitnest.user.grpc.UserResponse;
import az.fitnest.user.grpc.UserServiceGrpc;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class UserGrpcClient {
    private static final Logger log = LoggerFactory.getLogger(UserGrpcClient.class);

    @GrpcClient("user-backend")
    private UserServiceGrpc.UserServiceBlockingStub stub;

    public String getUserLanguage(Long userId) {
        if (userId == null) {
            return "az";
        }
        try {
            log.info("[gRPC] Fetching language for userId: {}", userId);
            GetUserByIdRequest request = GetUserByIdRequest.newBuilder()
                    .setUserId(userId)
                    .build();
            UserResponse response = stub.getUserById(request);
            String language = response.getLanguage();
            log.info("[gRPC] Found language '{}' for userId: {}", language, userId);
            return (language == null || language.isBlank()) ? "az" : language.toLowerCase();
        } catch (Exception e) {
            log.error("[gRPC] Error fetching language for userId: {}. Defaulting to 'az'. Error: {}", userId, e.getMessage());
            return "az";
        }
    }

    public UserResponse getUser(Long userId) {
        if (userId == null) {
            return null;
        }
        try {
            log.info("[gRPC] Fetching user details for userId: {}", userId);
            GetUserByIdRequest request = GetUserByIdRequest.newBuilder()
                    .setUserId(userId)
                    .build();
            return stub.getUserById(request);
        } catch (Exception e) {
            log.error("[gRPC] Error fetching user details for userId: {}. Error: {}", userId, e.getMessage());
            return null;
        }
    }
}
