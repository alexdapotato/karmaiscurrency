package akisei.karmacurrency;

/** Lives on a single open Java shop menu; closing the menu discards this state. */
public final class ShopConfirmation {
    private String pending;
    private boolean confirmed;
    public boolean mayPurchase(String key) {
        if (confirmed || key.equals(pending)) return true;
        pending=key;
        return false;
    }
    public void purchased() { confirmed=true; pending=null; }
    public void changedCategory() { if(!confirmed)pending=null; }
    public String prompt(String key) {
        return confirmed ? "Click to purchase." : key.equals(pending) ? "Click again to purchase." : "Click to review purchase.";
    }
}
