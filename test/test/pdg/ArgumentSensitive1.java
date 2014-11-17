package test.pdg;

// The query below is empty for Argument sensitive and not for type sensitive.
//let secret = pdg.fieldsNamed("secret") in
//let public = pdg.fieldsNamed("public") in
//let out = pdg.formalsOf("print") in
//pdg.shortestPath(secret, out)
public class ArgumentSensitive1 {

    static ArgumentSensitive1 out1;
    static ArgumentSensitive1 out2;
    static ArgumentSensitive1 secret;
    static ArgumentSensitive1 publicAS;

    public static void main(String[] args) {
        ArgumentSensitive1 rec = new ArgumentSensitive1();
        secret = new ArgumentSensitive1();
        publicAS = new ArgumentSensitive1Inner().foo();
        out1 = rec.bar(secret);
        out2 = rec.bar(publicAS);
        print(out2);
    }


    private static void print(ArgumentSensitive1 out2) {
    }

    private ArgumentSensitive1 bar(ArgumentSensitive1 as1) {
        return as1;
    }

    static class ArgumentSensitive1Inner {
        private ArgumentSensitive1 foo() {
            return new ArgumentSensitive1();
        }
    }

}
