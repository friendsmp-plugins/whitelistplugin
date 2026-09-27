package com.centers25.whitelistplugin;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.CompassMeta;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class ReferralsMigrationTest {
    @Test
    void repairsInvalidTicketAndPreservesAmount() {
        Inventory inventory = mock(Inventory.class);
        ItemStack ticket = mock(ItemStack.class);
        ItemStack identity = mock(ItemStack.class);
        ItemStack invalid = mock(ItemStack.class);
        ItemStack normalized = mock(ItemStack.class);
        CompassMeta meta = mock(CompassMeta.class);
        when(ticket.clone()).thenReturn(identity);
        when(inventory.getSize()).thenReturn(1);
        when(inventory.getItem(0)).thenReturn(invalid);
        when(invalid.getAmount()).thenReturn(16);
        when(invalid.clone()).thenReturn(normalized);
        when(normalized.getItemMeta()).thenReturn(meta);
        when(normalized.isSimilar(identity)).thenReturn(true);

        assertEquals(16, Referrals.migrate(inventory, ticket));
        verify(meta).setLodestone(null);
        verify(meta).setLodestoneTracked(false);
        verify(normalized).setItemMeta(meta);
        verify(identity).setAmount(16);
        verify(inventory).setItem(0, identity);
        verify(invalid, never()).setItemMeta(any());
    }

    @Test
    void leavesCurrentTicketsUnrelatedItemsAndEmptySlotsAlone() {
        Inventory inventory = mock(Inventory.class);
        ItemStack ticket = mock(ItemStack.class);
        ItemStack identity = mock(ItemStack.class);
        ItemStack current = mock(ItemStack.class);
        ItemStack unrelated = mock(ItemStack.class);
        ItemStack normalized = mock(ItemStack.class);
        when(ticket.clone()).thenReturn(identity);
        when(inventory.getSize()).thenReturn(3);
        when(inventory.getItem(0)).thenReturn(current);
        when(inventory.getItem(1)).thenReturn(unrelated);
        when(current.isSimilar(ticket)).thenReturn(true);
        when(unrelated.clone()).thenReturn(normalized);

        assertEquals(0, Referrals.migrate(inventory, ticket));
        verify(inventory, never()).setItem(anyInt(), any());
        verify(current, never()).clone();
    }
}
