import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.Bucket;

import java.util.List;

public class ListingBuckets {
    public static void main(String[] args) {
        try (S3Client s3 = S3Client.builder().region(Region.AP_SOUTH_1).build()) {
            List<Bucket> buckets = s3.listBuckets().buckets();
            System.out.println("Found " + buckets.size() + " bucket(s):");
            for (Bucket b : buckets) {
                System.out.println(" - " + b.name());
            }
        }
    }
}
