package dev.ole.test;

import dev.ole.fritzbox.FritzBox;
import dev.ole.fritzbox.model.FritzOS;
import dev.ole.fritzbox.model.InternetInfo;

import java.util.List;

public class TestLaunch {

    public static void main(String[] args) {
        FritzBox fritzBox = new FritzBox(
                "fritz.box",
                "username",
                "password"
        );

        fritzBox.login();

        // Get the current information about the internet connections.
        InternetInfo internetInfo = fritzBox.getInternetInfo().get(0);
        System.out.println(internetInfo.isConnected());
        System.out.println(internetInfo.isIpv4Connected());
        System.out.println(internetInfo.isIpv4Connected());
        System.out.println(internetInfo.getIpv6());
        System.out.println(internetInfo.isIpv6Connected());
        System.out.println(internetInfo.getProviderName());
        System.out.println(internetInfo.getUpstreamBits());
        System.out.println(internetInfo.getMediumUpstreamBits());
        System.out.println(internetInfo.getDownstreamBits());
        System.out.println(internetInfo.getMediumDownstreamBits());

        fritzBox.reconnect();

        FritzOS fritzOS = fritzBox.getFritzOS();
        System.out.println(fritzOS.getVersion());
        System.out.println(fritzOS.getDateOfLastUpdate());
        System.out.println(fritzOS.getDateOfLastAutomaticCheck());

    }
}
