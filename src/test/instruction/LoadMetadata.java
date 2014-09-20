package test.instruction;

public class LoadMetadata {
    public static void main(String[] args) throws ClassNotFoundException {
        LoadMetadata.class.getClassLoader().loadClass("test.pdg.instruction.LoadMetadata");
    }
}
