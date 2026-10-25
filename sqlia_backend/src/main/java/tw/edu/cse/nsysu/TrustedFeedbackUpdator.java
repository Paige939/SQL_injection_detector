package tw.edu.cse.nsysu;

import tw.edu.cse.nsysu.api.service.ModelStateService;

public class TrustedFeedbackUpdator {
    private final ModelStateService modelState;

    public TrustedFeedbackUpdator(ModelStateService modelState) {
        this.modelState = modelState;
    }

    // Delegate feedback to the single service that owns ML and ARM state.
    public void applyFeedback(String rawSql, int verifiedLabel, boolean isVerifiedBenign)
            throws Exception {
        modelState.applyVerifiedFeedback(rawSql, verifiedLabel, isVerifiedBenign);
    }
}