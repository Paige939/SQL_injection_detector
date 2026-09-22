package tw.edu.cse.nsysu.preprocess;

import java.util.ArrayList;
import java.util.List;

public class LabelValidator {
    //Return the filtered, whose label has tranferred to int(0/1), and drop the invalid ones
    public static List<RawRecord> validate(List<RawRecord> records) {
        List<RawRecord> valid = new ArrayList<>();
        int dropped = 0;

        for (RawRecord r : records) {
            String label = r.rawLabel == null ? "" : r.rawLabel.trim();
            if ("0".equals(label) || "1".equals(label)) { //If label = 1/0 means valid
                valid.add(r);
            } else {
                dropped++;
            }
        }

        System.out.println("LabelValidator: dropped " + dropped + " invalid label rows.");
        return valid;
    }
}