package net.ximatai.muyun.spring.iam.user;

/** The deliberately small self-service surface for an employee's contact information. */
public record UpdateCurrentUserProfileRequest(String mobile, String email, String avatarAssetId) {
}
