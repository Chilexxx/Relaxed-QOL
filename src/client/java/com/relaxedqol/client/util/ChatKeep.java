package com.relaxedqol.client.util;

/**
 * Chat's Keep On Leave backup (ChatComponentMixin adds this to the game's chat): a copy of the chat lines is taken
 * as you leave a server, and put back once you are in a world again if the game wiped them some other way.
 */
public interface ChatKeep {
    void relaxed$save();

    void relaxed$restoreIfLost();
}
